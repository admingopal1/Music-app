package com.app.musicplayer

import androidx.compose.foundation.background
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun FirebaseAuthGate(content: @Composable () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    var signedIn by remember { mutableStateOf(auth.currentUser != null) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { signedIn = it.currentUser != null }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    if (signedIn) {
        content()
    } else {
        // The sign-in screen has a dark background but sits outside MusicTheme, so it fell back to the
        // light default scheme and typed text came out dark on dark. Give it a dark scheme.
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFCA9AFF))) { AuthScreen(auth) }
    }
}

@Composable
private fun AuthScreen(auth: FirebaseAuth) {
    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val googleSignInOptions = remember(context) {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember(context) {
        GoogleSignIn.getClient(context, googleSignInOptions)
    }
    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            busy = true
            message = null
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                auth.signInWithCredential(credential).addOnCompleteListener { task ->
                    busy = false
                    if (task.isSuccessful) {
                        auth.currentUser?.let { user ->
                            val profile = hashMapOf<String, Any>(
                                "email" to (user.email ?: ""),
                                "displayName" to (user.displayName ?: ""),
                                "photoUrl" to (user.photoUrl?.toString() ?: ""),
                                "provider" to "google",
                                "updatedAt" to FieldValue.serverTimestamp(),
                            )
                            FirebaseFirestore.getInstance()
                                .collection("users")
                                .document(user.uid)
                                .set(profile, com.google.firebase.firestore.SetOptions.merge())
                                .addOnFailureListener { error ->
                                    message = "Signed in, but profile sync failed: ${error.localizedMessage ?: "check Firestore rules"}"
                                }
                        }
                    } else {
                        message = task.exception?.localizedMessage
                            ?: "Google authentication failed. Please try again."
                    }
                }
            } catch (error: ApiException) {
                busy = false
                message = error.localizedMessage ?: "Google sign-in failed. Please try again."
            }
        } else {
            busy = false
            message = "Google sign-in cancelled."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF241342), Color(0xFF17152F), Color(0xFF090912)))).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "MUSIC PLAYER",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFFCA9AFF),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (isRegister) "Create your account" else "Welcome back",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFE7B6FF), fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Sign in to keep your music preferences and playlists connected.",
            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.72f),
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim(); message = null },
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            shape = RoundedCornerShape(18.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; message = null },
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(18.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (email.isBlank() || !email.contains("@") || password.length < 6) {
                    message = "Enter a valid email and a password with at least 6 characters."
                    return@Button
                }
                busy = true
                message = null
                val task = if (isRegister) {
                    auth.createUserWithEmailAndPassword(email, password)
                } else {
                    auth.signInWithEmailAndPassword(email, password)
                }
                task.addOnCompleteListener { result ->
                    busy = false
                    if (result.isSuccessful) {
                        val user = auth.currentUser
                        if (user != null) {
                            val profile = hashMapOf<String, Any>(
                                "email" to (user.email ?: email),
                                "updatedAt" to FieldValue.serverTimestamp(),
                            )
                            FirebaseFirestore.getInstance()
                                .collection("users")
                                .document(user.uid)
                                .set(profile, com.google.firebase.firestore.SetOptions.merge())
                                .addOnFailureListener { error ->
                                    message = "Signed in, but profile sync failed: ${error.localizedMessage ?: "check Firestore rules"}"
                                }
                        }
                    } else {
                        message = result.exception?.localizedMessage ?: "Authentication failed. Please try again."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            enabled = !busy,
        ) {
            Text(if (busy) "Please wait…" else if (isRegister) "Create account" else "Sign in")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                busy = true
                message = null
                googleLauncher.launch(googleSignInClient.signInIntent)
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            enabled = !busy,
        ) {
            Text(if (busy) "Please wait…" else "Continue with Google")
        }
        if (!isRegister) {
            TextButton(
                onClick = {
                    if (email.isBlank() || !email.contains("@")) {
                        message = "Enter your email first to reset your password."
                    } else {
                        busy = true
                        auth.sendPasswordResetEmail(email).addOnCompleteListener { result ->
                            busy = false
                            message = if (result.isSuccessful) "Password reset email sent."
                            else result.exception?.localizedMessage ?: "Could not send reset email."
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Forgot password?") }
        }
        TextButton(
            onClick = { isRegister = !isRegister; message = null },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isRegister) "Already have an account? Sign in" else "New here? Create an account")
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
