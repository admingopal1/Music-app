package com.app.musicplayer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

private val AuthViolet = Color(0xFFB77BFF)
private val AuthVioletDeep = Color(0xFF7B3FE4)
private val AuthPink = Color(0xFFEC5BB0)
private val AuthInk = Color(0xFF07060F)

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
        MaterialTheme(colorScheme = darkColorScheme(primary = AuthViolet)) {
            AuthScreen(auth)
        }
    }
}

@Composable
private fun AuthScreen(auth: FirebaseAuth) {
    var isRegister by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current

    val googleSignInOptions = remember(context) {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember(context) {
        GoogleSignIn.getClient(context, googleSignInOptions)
    }

    fun saveProfile(extra: Map<String, Any>) {
        val user = auth.currentUser ?: return
        val profile = hashMapOf<String, Any>(
            "email" to (user.email ?: ""),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        profile.putAll(extra)
        FirebaseFirestore.getInstance().collection("users").document(user.uid)
            .set(profile, com.google.firebase.firestore.SetOptions.merge())
            .addOnFailureListener { error ->
                message = "Signed in, but profile sync failed: ${error.localizedMessage ?: "check Firestore rules"}"
            }
    }

    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        busy = true
        message = null
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                busy = false
                message = "Google sign-in returned no ID token. Check the Firebase Web client ID."
            } else {
                auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                    .addOnCompleteListener { task ->
                        busy = false
                        if (task.isSuccessful) {
                            val user = auth.currentUser
                            saveProfile(
                                mapOf(
                                    "displayName" to (user?.displayName ?: ""),
                                    "photoUrl" to (user?.photoUrl?.toString() ?: ""),
                                    "provider" to "google",
                                )
                            )
                        } else {
                            message = task.exception?.localizedMessage
                                ?: "Google authentication failed. Please try again."
                        }
                    }
            }
        } catch (error: ApiException) {
            busy = false
            message = when (error.statusCode) {
                12501 -> "Google sign-in was cancelled (status 12501)."
                12500 -> "Google sign-in could not start (status 12500). Update Google Play services and retry."
                12502 -> "Another sign-in is already in progress (status 12502). Try again shortly."
                10 -> "Google sign-in configuration error (status 10). Check Firebase SHA fingerprints and Web client ID."
                7 -> "Network error (status 7). Check your internet connection."
                else -> "Google sign-in failed (status ${error.statusCode}): ${error.localizedMessage ?: "Unknown error"}"
            }
        } catch (error: Exception) {
            busy = false
            message = "Google sign-in failed: ${error.localizedMessage ?: "Unknown error"}"
        }
    }

    fun submitEmail() {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@") || password.length < 6) {
            message = "Enter a valid email and a password with at least 6 characters."
            return
        }
        focus.clearFocus()
        busy = true
        message = null
        info = null
        val task = if (isRegister) {
            auth.createUserWithEmailAndPassword(cleanEmail, password)
        } else {
            auth.signInWithEmailAndPassword(cleanEmail, password)
        }
        task.addOnCompleteListener { result ->
            busy = false
            if (result.isSuccessful) {
                saveProfile(mapOf("provider" to "password"))
            } else {
                message = result.exception?.localizedMessage
                    ?: "Authentication failed. Please try again."
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = AuthViolet,
        unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
        focusedLabelColor = AuthViolet,
        unfocusedLabelColor = Color.White.copy(alpha = 0.62f),
        focusedLeadingIconColor = AuthViolet,
        unfocusedLeadingIconColor = Color.White.copy(alpha = 0.62f),
        focusedTrailingIconColor = AuthViolet,
        unfocusedTrailingIconColor = Color.White.copy(alpha = 0.62f),
        cursorColor = AuthViolet,
        focusedContainerColor = Color.Black.copy(alpha = 0.22f),
        unfocusedContainerColor = Color.Black.copy(alpha = 0.22f),
    )

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF1B0F36), Color(0xFF110E24), AuthInk))
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(82.dp).clip(RoundedCornerShape(25.dp))
                    .background(Brush.linearGradient(listOf(AuthViolet, AuthVioletDeep, AuthPink)))
                    .border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(25.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = "Music Player", tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("Music Player", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(5.dp))
            Text(
                "Your music, playlists and favourites — everywhere.",
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.045f))))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(30.dp))
                    .padding(20.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.28f)).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(false to "Sign in", true to "Create account").forEach { (register, label) ->
                        val selected = isRegister == register
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) Brush.linearGradient(listOf(AuthViolet, AuthVioletDeep))
                                    else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                                )
                                .clickable(enabled = !busy) {
                                    isRegister = register
                                    message = null
                                    info = null
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        busy = true
                        message = null
                        info = null
                        googleLauncher.launch(googleSignInClient.signInIntent)
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1F1F1F),
                        disabledContainerColor = Color.White.copy(alpha = 0.55f),
                    ),
                ) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFF1F3F4)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text("Continue with Google", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.15f)))
                    Text("  or use email  ", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.15f)))
                }
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.replace(" ", ""); message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                    singleLine = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    singleLine = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitEmail() }),
                )

                if (!isRegister) {
                    TextButton(
                        onClick = {
                            val cleanEmail = email.trim()
                            if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
                                message = "Enter your email first to reset your password."
                            } else {
                                busy = true
                                message = null
                                info = null
                                auth.sendPasswordResetEmail(cleanEmail).addOnCompleteListener { result ->
                                    busy = false
                                    if (result.isSuccessful) info = "Password reset email sent to $cleanEmail."
                                    else message = result.exception?.localizedMessage ?: "Could not send reset email."
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Forgot password?", color = AuthViolet)
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }

                message?.let {
                    Text(
                        it,
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFB3261E).copy(alpha = 0.18f)).padding(12.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                info?.let {
                    Text(
                        it,
                        color = Color(0xFFB7F5C8),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1B8A4B).copy(alpha = 0.18f)).padding(12.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Button(
                    onClick = { submitEmail() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                ) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(
                                if (busy) listOf(Color(0xFF4A3A70), Color(0xFF3A2E5A))
                                else listOf(AuthViolet, AuthVioletDeep, AuthPink)
                            )
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
                        } else {
                            Text(
                                if (isRegister) "Create account" else "Sign in",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Your playlists and favourites stay connected to your account.",
                color = Color.White.copy(alpha = 0.42f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
