package com.app.musicplayer

import android.content.Context
import android.util.Patterns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.app.musicplayer.ui.theme.DefaultAccent
import com.app.musicplayer.ui.theme.MusicTheme
import com.app.musicplayer.ui.theme.glass
import com.app.musicplayer.ui.theme.glassBackdrop
import com.app.musicplayer.ui.theme.glassFieldColors
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
        // The login screen sits outside the main app theme, so it brings its own dark glass theme.
        MusicTheme(accent = DefaultAccent, amoled = true) { AuthScreen(auth) }
    }
}

@Composable
private fun AuthScreen(auth: FirebaseAuth) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var isRegister by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(true) }

    fun fail(text: String) {
        message = text
        messageIsError = true
    }

    fun info(text: String) {
        message = text
        messageIsError = false
    }

    fun submit() {
        if (busy) return
        val mail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(mail).matches()) {
            fail("Enter a valid email address.")
            return
        }
        if (password.length < 6) {
            fail("Password must be at least 6 characters.")
            return
        }
        if (isRegister && password != confirm) {
            fail("The two passwords do not match.")
            return
        }
        focus.clearFocus()
        busy = true
        message = null
        scope.launch {
            try {
                if (isRegister) auth.createUserWithEmailAndPassword(mail, password).await()
                else auth.signInWithEmailAndPassword(mail, password).await()
                auth.currentUser?.let { saveProfile(it, mail) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(friendlyAuthError(e))
            } finally {
                busy = false
            }
        }
    }

    fun resetPassword() {
        if (busy) return
        val mail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(mail).matches()) {
            fail("Enter your email above first, then tap “Forgot password?”.")
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                auth.sendPasswordResetEmail(mail).await()
                info("Password reset email sent to $mail.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(friendlyAuthError(e))
            } finally {
                busy = false
            }
        }
    }

    fun googleSignIn() {
        if (busy) return
        val clientId = webClientId(context)
        if (clientId == null) {
            fail(
                "Google sign-in is not set up yet. In Firebase, enable the Google provider, add this app's " +
                    "SHA-1 key, and replace google-services.json (see GOOGLE_SIGNIN_SETUP.md)."
            )
            return
        }
        focus.clearFocus()
        busy = true
        message = null
        scope.launch {
            try {
                val option = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(clientId)
                    .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(context).getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val google = GoogleIdTokenCredential.createFrom(credential.data)
                    auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
                    auth.currentUser?.let { saveProfile(it, google.id) }
                } else {
                    fail("Unexpected response from Google. Please try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GetCredentialCancellationException) {
                // The person closed the Google sheet: nothing to report.
            } catch (e: NoCredentialException) {
                fail("No Google account found on this phone. Add one in Settings → Accounts, then try again.")
            } catch (e: GetCredentialException) {
                fail("Google sign-in failed: ${e.message ?: "unknown error"}")
            } catch (e: Exception) {
                fail(friendlyAuthError(e))
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().glassBackdrop(DefaultAccent, Color(0xFFEC407A))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(76.dp).glass(CircleShape, tint = DefaultAccent.copy(alpha = 0.30f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Music Player",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Your music, playlists and favourites, everywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(32.dp), tint = Color(0xFF0E0B18).copy(alpha = 0.35f))
                    .padding(20.dp),
            ) {
                // Sign in / Create account switch
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AuthTab("Sign in", !isRegister, Modifier.weight(1f)) {
                        isRegister = false
                        message = null
                    }
                    AuthTab("Create account", isRegister, Modifier.weight(1f)) {
                        isRegister = true
                        message = null
                    }
                }
                Spacer(Modifier.height(18.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    shape = RoundedCornerShape(18.dp),
                    colors = glassFieldColors(),
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
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (isRegister) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focus.moveFocus(FocusDirection.Down) },
                        onDone = { submit() },
                    ),
                    shape = RoundedCornerShape(18.dp),
                    colors = glassFieldColors(),
                )
                if (isRegister) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it; message = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Confirm password") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        shape = RoundedCornerShape(18.dp),
                        colors = glassFieldColors(),
                    )
                }

                if (!isRegister) {
                    TextButton(
                        onClick = { resetPassword() },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Forgot password?") }
                } else {
                    Spacer(Modifier.height(8.dp))
                }

                message?.let { text ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (messageIsError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = if (messageIsError) MaterialTheme.colorScheme.error else Color(0xFF7BE0A5),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (messageIsError) MaterialTheme.colorScheme.error else Color(0xFF7BE0A5),
                        )
                    }
                }

                Button(
                    onClick = { submit() },
                    enabled = !busy,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    } else {
                        Text(if (isRegister) "Create account" else "Sign in", fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.18f)))
                    Text(
                        "  or  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.60f),
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.18f)))
                }
                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = { googleSignIn() },
                    enabled = !busy,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = Color.White,
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Box(
                        Modifier.size(26.dp).background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.size(12.dp))
                    Text("Continue with Google", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Signing in keeps your playlists, favourites and ratings in sync.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AuthTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .glass(RoundedCornerShape(14.dp), tint = if (selected) accent.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * The Web client ID that Firebase generates when Google sign-in is enabled. It only exists in
 * google-services.json after the Google provider is enabled and the app's SHA-1 key is added.
 */
private fun webClientId(context: Context): String? {
    val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    return if (id != 0) context.getString(id).takeIf { it.isNotBlank() } else null
}

/** Fire-and-forget: the write is queued by Firestore even if this screen leaves the composition. */
private fun saveProfile(user: FirebaseUser, fallbackEmail: String) {
    val profile = hashMapOf<String, Any>(
        "email" to (user.email ?: fallbackEmail),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
    FirebaseFirestore.getInstance()
        .collection("users")
        .document(user.uid)
        .set(profile, SetOptions.merge())
}

private fun friendlyAuthError(e: Exception): String = when (e) {
    is FirebaseAuthWeakPasswordException -> "Choose a stronger password (at least 6 characters)."
    is FirebaseAuthInvalidUserException -> "No account was found for this email."
    is FirebaseAuthInvalidCredentialsException -> "Incorrect email or password."
    is FirebaseAuthUserCollisionException -> "An account with this email already exists. Try signing in."
    is FirebaseTooManyRequestsException -> "Too many attempts. Please wait a few minutes and try again."
    is FirebaseNetworkException -> "No internet connection. Check your network and try again."
    else -> e.localizedMessage ?: "Authentication failed. Please try again."
}
