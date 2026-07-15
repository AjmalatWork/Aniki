package com.aniki.anikiai.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniki.anikiai.auth.AuthManager
import com.aniki.anikiai.ui.theme.AnikiTheme
import com.aniki.anikiai.ui.theme.Ink
import com.aniki.anikiai.ui.theme.Kon
import com.aniki.anikiai.ui.theme.OnDarkBody
import com.aniki.anikiai.ui.theme.OnDarkMuted
import com.aniki.anikiai.ui.theme.Paper
import com.aniki.anikiai.ui.theme.PlexMono
import com.aniki.anikiai.ui.theme.SealDark
import com.aniki.anikiai.ui.theme.SealMark
import com.aniki.anikiai.ui.theme.weightedShadow
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch

/**
 * First-run / brand screen (mockup plate 01). One of the two dark-ground
 * screens by design — wraps itself in AnikiTheme(darkGround = true).
 */
@Composable
fun OnboardingScreen(
    onContinueAsGuest: () -> Unit,
    onSignedIn: (FirebaseUser) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val authManager = remember { AuthManager(context) }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AnikiTheme(darkGround = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Kon glow at the top fading into ink, per the mockup gradient.
                .background(
                    Brush.radialGradient(
                        colors = listOf(Kon, Ink),
                        center = Offset(0.5f, 0.12f),
                        radius = Float.POSITIVE_INFINITY
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(0.75f))

                SealMark(size = 96.dp, filled = true)

                Spacer(Modifier.height(34.dp))

                Text(
                    text = buildAnnotatedString {
                        append("Aniki")
                        withStyle(SpanStyle(color = SealDark)) { append(" AI") }
                    },
                    style = MaterialTheme.typography.displaySmall,
                    color = Paper
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "ANIKI · 兄貴 · BIG BROTHER",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        letterSpacing = 4.sp
                    ),
                    color = OnDarkMuted
                )

                Spacer(Modifier.height(22.dp))

                Text(
                    text = buildAnnotatedString {
                        append("Save anything. Aniki ")
                        withStyle(SpanStyle(color = Paper, fontWeight = FontWeight.SemiBold)) {
                            append("reads it, tags it, and files it")
                        }
                        append(" — then brings it back when you'll want it.")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnDarkBody,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 264.dp)
                )

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = {
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            val result = authManager.signInWithGoogle()
                            isLoading = false
                            result.onSuccess(onSignedIn).onFailure {
                                errorMessage = it.message ?: "Sign-in failed"
                            }
                        }
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Paper,
                        contentColor = Ink,
                        disabledContainerColor = Paper.copy(alpha = 0.5f),
                        disabledContentColor = Ink.copy(alpha = 0.5f)
                    ),
                    contentPadding = ButtonDefaults.ContentPadding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weightedShadow(RoundedCornerShape(13.dp), ambient = 12.dp, contact = 4.dp)
                        .height(50.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        GoogleDot()
                        Text(
                            "Continue with Google",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp)
                        )
                    }
                }

                Spacer(Modifier.height(11.dp))

                TextButton(onClick = onContinueAsGuest, enabled = !isLoading) {
                    Text(
                        "Continue as guest",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = PlexMono,
                            fontSize = 12.sp
                        ),
                        color = OnDarkMuted
                    )
                }

                if (isLoading) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(color = SealDark)
                }
                errorMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = SealDark,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

/** The four-color Google dot from the mockup CTA. */
@Composable
private fun GoogleDot() {
    Box(
        modifier = Modifier
            .size(17.dp)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(
                        Color(0xFFEA4335),
                        Color(0xFFFBBC05),
                        Color(0xFF34A853),
                        Color(0xFF4285F4),
                        Color(0xFFEA4335)
                    )
                )
            )
    )
}
