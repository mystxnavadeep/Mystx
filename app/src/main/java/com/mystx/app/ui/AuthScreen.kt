package com.mystx.app.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mystx.app.manager.AuthManager
import com.mystx.app.ui.components.MystAuroraBackdrop
import com.mystx.app.ui.components.MystCard
import com.mystx.app.ui.components.MystGradientButton
import com.mystx.app.ui.components.MystTonalButton
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    onAuthSuccess: (AuthManager.UserAccount) -> Unit,
    onContinueAsGuest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isSignUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        MystAuroraBackdrop()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Mystx",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isSignUp) "Create your account to sync & unlock Pro"
                else "Sign in to continue to Mystx",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            MystCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isSignUp) {
                        MystGradientButton(
                            text = "Sign In",
                            onClick = {
                                isSignUp = false
                                statusMessage = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                        MystTonalButton(
                            text = "Sign Up",
                            onClick = {
                                isSignUp = true
                                statusMessage = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        MystTonalButton(
                            text = "Sign In",
                            onClick = {
                                isSignUp = false
                                statusMessage = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                        MystGradientButton(
                            text = "Sign Up",
                            onClick = {
                                isSignUp = true
                                statusMessage = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isSignUp) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (!isSignUp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            enabled = !isLoading,
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    statusMessage = null
                                    val res = AuthManager.sendPasswordReset(context, email)
                                    isLoading = false
                                    res.fold(
                                        onSuccess = {
                                            isError = false
                                            statusMessage = "Password reset link sent to ${email.trim()}."
                                        },
                                        onFailure = { err ->
                                            isError = true
                                            statusMessage = err.message ?: "Could not send reset email."
                                        }
                                    )
                                }
                            }
                        ) {
                            Text("Forgot password?", fontSize = 12.sp)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                } else {
                    MystGradientButton(
                        text = if (isSignUp) "Create Account" else "Sign In",
                        onClick = {
                            scope.launch {
                                isLoading = true
                                statusMessage = null
                                val result = if (isSignUp) {
                                    AuthManager.signUpWithEmail(context, name, email, password)
                                } else {
                                    AuthManager.signInWithEmail(context, email, password)
                                }
                                isLoading = false
                                result.fold(
                                    onSuccess = { account -> onAuthSuccess(account) },
                                    onFailure = { err ->
                                        isError = true
                                        statusMessage = err.message ?: "Authentication failed."
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    MystTonalButton(
                        text = "Continue with Google",
                        onClick = {
                            scope.launch {
                                isLoading = true
                                statusMessage = null
                                val result = AuthManager.signInWithGoogle(context)
                                isLoading = false
                                result.fold(
                                    onSuccess = { account -> onAuthSuccess(account) },
                                    onFailure = { err ->
                                        isError = true
                                        statusMessage = err.message ?: "Google Sign-In failed."
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            TextButton(
                enabled = !isLoading,
                onClick = onContinueAsGuest
            ) {
                Text(
                    text = "Continue as Guest",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
