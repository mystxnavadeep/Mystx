package com.mystx.app.manager

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lightweight Firebase Auth + Cloud Firestore user account & Pro-status manager.
 *
 * Gracefully checks whether Firebase has been configured via `app/google-services.json`
 * so builds without the config file still run cleanly in Guest mode without crashing.
 */
object AuthManager {

    private const val COLLECTION_USERS = "users"

    data class UserAccount(
        val uid: String,
        val email: String,
        val displayName: String,
        val isPro: Boolean = false,
        val plan: String = "free",
        val proExpiresAtMillis: Long = 0L
    ) {
        val isProActive: Boolean
            get() = isPro && (proExpiresAtMillis == 0L || proExpiresAtMillis > System.currentTimeMillis())
    }

    /** Returns true if Firebase is initialized (i.e. valid app/google-services.json is bundled). */
    fun isFirebaseConfigured(context: Context): Boolean {
        return try {
            if (FirebaseApp.getApps(context.applicationContext).isNotEmpty()) {
                true
            } else {
                FirebaseApp.initializeApp(context.applicationContext) != null
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun currentUser(context: Context): UserAccount? {
        if (!isFirebaseConfigured(context)) return null
        val user = try {
            FirebaseAuth.getInstance().currentUser
        } catch (_: Throwable) {
            null
        } ?: return null
        return user.toBasicAccount()
    }

    suspend fun signInWithEmail(
        context: Context,
        email: String,
        password: String
    ): Result<UserAccount> {
        if (!isFirebaseConfigured(context)) {
            return Result.failure(IllegalStateException("Firebase is not configured yet. Add app/google-services.json to enable login."))
        }
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty() || password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email and password are required."))
        }
        return try {
            val result = FirebaseAuth.getInstance()
                .signInWithEmailAndPassword(cleanEmail, password)
                .awaitResult()
            val user = result.user
                ?: return Result.failure(IllegalStateException("Sign in failed. Please try again."))
            // Ensure profile doc exists and fetch live Pro fields
            fetchUserProfile(context).fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.success(user.toBasicAccount()) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(friendlyAuthError(e)))
        }
    }

    suspend fun signUpWithEmail(
        context: Context,
        name: String,
        email: String,
        password: String
    ): Result<UserAccount> {
        if (!isFirebaseConfigured(context)) {
            return Result.failure(IllegalStateException("Firebase is not configured yet. Add app/google-services.json to enable sign up."))
        }
        val cleanName = name.trim()
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            return Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
        }
        return try {
            val result = FirebaseAuth.getInstance()
                .createUserWithEmailAndPassword(cleanEmail, password)
                .awaitResult()
            val user = result.user
                ?: return Result.failure(IllegalStateException("Account creation failed. Please try again."))

            if (cleanName.isNotEmpty()) {
                try {
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(cleanName)
                        .build()
                    user.updateProfile(profileUpdates).awaitResult()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }

            // Create initial Firestore user doc ready for Pro subscription management
            try {
                val doc = hashMapOf(
                    "email" to cleanEmail,
                    "name" to cleanName.ifEmpty { cleanEmail.substringBefore("@") },
                    "isPro" to false,
                    "plan" to "free",
                    "proExpiresAt" to 0L,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                FirebaseFirestore.getInstance()
                    .collection(COLLECTION_USERS)
                    .document(user.uid)
                    .set(doc, SetOptions.merge())
                    .awaitResult()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Auth succeeded even if Firestore is offline; doc will sync later.
            }

            Result.success(
                UserAccount(
                    uid = user.uid,
                    email = cleanEmail,
                    displayName = cleanName.ifEmpty { cleanEmail.substringBefore("@") },
                    isPro = false,
                    plan = "free",
                    proExpiresAtMillis = 0L
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(friendlyAuthError(e)))
        }
    }


    suspend fun signInWithGoogle(context: Context): Result<UserAccount> {
        if (!isFirebaseConfigured(context)) {
            return Result.failure(IllegalStateException("Firebase is not configured yet. Add app/google-services.json to enable Google Sign-In."))
        }
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        val webClientId = if (resId != 0) context.getString(resId) else ""
        if (webClientId.isBlank()) {
            return Result.failure(IllegalStateException("Google Sign-In is not enabled in google-services.json. Enable Google in Firebase Auth, add your SHA-1, and re-download google-services.json."))
        }
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val credentialManager = CredentialManager.create(context)
            val response = credentialManager.getCredential(context, request)
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            val authResult = FirebaseAuth.getInstance()
                .signInWithCredential(firebaseCred)
                .awaitResult()
            val user = authResult.user
                ?: return Result.failure(IllegalStateException("Google Sign-In failed. Please try again."))

            // Create initial Firestore doc only if it doesn't exist yet (never overwrite existing Pro fields)
            try {
                val docRef = FirebaseFirestore.getInstance()
                    .collection(COLLECTION_USERS)
                    .document(user.uid)
                val snap = docRef.get().awaitResult()
                if (!snap.exists()) {
                    val doc = hashMapOf(
                        "email" to user.email.orEmpty(),
                        "name" to (user.displayName?.takeIf { it.isNotBlank() } ?: user.email?.substringBefore("@").orEmpty()),
                        "isPro" to false,
                        "plan" to "free",
                        "proExpiresAt" to 0L,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    docRef.set(doc, SetOptions.merge()).awaitResult()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}

            fetchUserProfile(context).fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.success(user.toBasicAccount()) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetCredentialCancellationException) {
            Result.failure(IllegalStateException("Google Sign-In cancelled."))
        } catch (e: Exception) {
            Result.failure(Exception(friendlyAuthError(e)))
        }
    }

    suspend fun sendPasswordReset(context: Context, email: String): Result<Unit> {
        if (!isFirebaseConfigured(context)) {
            return Result.failure(IllegalStateException("Firebase is not configured yet."))
        }
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            return Result.failure(IllegalArgumentException("Enter your email first to receive a reset link."))
        }
        return try {
            FirebaseAuth.getInstance().sendPasswordResetEmail(cleanEmail).awaitResult()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(friendlyAuthError(e)))
        }
    }

    suspend fun fetchUserProfile(context: Context): Result<UserAccount> {
        if (!isFirebaseConfigured(context)) {
            return Result.failure(IllegalStateException("Firebase not configured"))
        }
        val user = FirebaseAuth.getInstance().currentUser
            ?: return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val snap = FirebaseFirestore.getInstance()
                .collection(COLLECTION_USERS)
                .document(user.uid)
                .get()
                .awaitResult()
            if (snap.exists()) {
                val isPro = snap.getBoolean("isPro") ?: false
                val plan = snap.getString("plan") ?: "free"
                val expiresAt = snap.getLong("proExpiresAt") ?: 0L
                val name = snap.getString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?: user.displayName
                    ?: user.email?.substringBefore("@").orEmpty()
                Result.success(
                    UserAccount(
                        uid = user.uid,
                        email = user.email ?: snap.getString("email").orEmpty(),
                        displayName = name,
                        isPro = isPro,
                        plan = plan,
                        proExpiresAtMillis = expiresAt
                    )
                )
            } else {
                Result.success(user.toBasicAccount())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut(context: Context) {
        if (!isFirebaseConfigured(context)) return
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Throwable) {}
    }

    private fun FirebaseUser.toBasicAccount(): UserAccount = UserAccount(
        uid = uid,
        email = email.orEmpty(),
        displayName = displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore("@").orEmpty()
    )

    private fun friendlyAuthError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) ||
                msg.contains("wrong-password", ignoreCase = true) ||
                msg.contains("user-not-found", ignoreCase = true) ->
                "Invalid email or password."
            msg.contains("email-already-in-use", ignoreCase = true) ||
                msg.contains("already in use", ignoreCase = true) ->
                "An account with this email already exists."
            msg.contains("badly formatted", ignoreCase = true) ->
                "Please enter a valid email address."
            msg.contains("network error", ignoreCase = true) ->
                "Network error. Check your internet connection."
            msg.isNotBlank() -> msg
            else -> "Authentication failed. Please try again."
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exc ->
            if (cont.isActive) cont.resumeWithException(exc)
        }
        addOnCanceledListener {
            cont.cancel()
        }
    }
}
