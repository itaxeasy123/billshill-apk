package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AccountingViewModel
import com.example.ui.theme.*

@Composable
fun AuthScreen(viewModel: AccountingViewModel) {
    // Still starts empty: it shipped prefilled with a working-looking number and OTP.
    // The difference now is that the code is genuinely sent and genuinely checked.
    var phoneNumber by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    val otpSent by viewModel.otpSentState.collectAsState()
    val otpBusy by viewModel.otpBusyState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(RoyalPurplePrimary, DeepPurpleSecondary, DarkPurpleVariant)
                )
            )
            .padding(24.dp)
            .testTag("auth_screen_container"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = LavenderContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Set up your books",
                            tint = RoyalPurplePrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Mobile Accounting",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "Frictionless Indian GST & Business Accounting",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Mobile Number") },
                    prefix = { Text("+91 ") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone_number_input")
                )

                if (otpSent) {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = otpCode,
                        onValueChange = { otpCode = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text("Enter the code from the SMS") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        singleLine = true,
                        enabled = !otpBusy,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("otp_code_input")
                    )
                    Row {
                        TextButton(onClick = { viewModel.resendOtp(phoneNumber) }, enabled = !otpBusy) {
                            Text("Resend code", fontSize = 12.sp)
                        }
                        TextButton(onClick = { viewModel.resetOtpFlow(); otpCode = "" }, enabled = !otpBusy) {
                            Text("Change number", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Your books stay on this device. The number is verified by SMS " +
                        "so only you can open them.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    // A real OTP now. This used to send nothing and accept anything —
                    // loginWithOtp returned true for any input, next to fields prefilled
                    // with a working-looking number and code.
                    onClick = {
                        if (otpSent) viewModel.verifyOtpAndLogin(phoneNumber, otpCode)
                        else viewModel.sendOtp(phoneNumber)
                    },
                    enabled = !otpBusy && if (otpSent) otpCode.length >= 4 else phoneNumber.filter { it.isDigit() }.length >= 10,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("login_action_button")
                ) {
                    Text(
                        text = when {
                            otpBusy -> "Please wait…"
                            otpSent -> "Verify & Get Started"
                            else -> "Send OTP"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Verified by SMS • Your books never leave this device",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
