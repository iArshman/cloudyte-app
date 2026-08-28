package com.example.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.api.ApiClient
import com.example.api.LoginRequest
import com.example.api.RegisterRequest
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isRegisterMode = MutableStateFlow(false)
    val isRegisterMode: StateFlow<Boolean> = _isRegisterMode.asStateFlow()

    fun toggleMode() {
        _isRegisterMode.value = !_isRegisterMode.value
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun authenticate(username: String, password: String, onSuccess: () -> Unit) {
        val cleanUsername = username.trim()
        val cleanPassword = password.trim()
        
        if (cleanUsername.isEmpty() || cleanPassword.isEmpty()) {
            _error.value = "Please enter both username and password."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Ensure no previous invalid token is cached
                ApiClient.saveToken(null)

                if (_isRegisterMode.value) {
                    val regResponse = ApiClient.apiService.register(RegisterRequest(cleanUsername, cleanPassword))
                    if (regResponse.isSuccessful) {
                        // After register, attempt auto-login
                        val loginResponse = ApiClient.apiService.login(LoginRequest(cleanUsername, cleanPassword))
                        if (loginResponse.isSuccessful) {
                            val token = loginResponse.body()?.token
                            if (!token.isNullOrEmpty()) {
                                ApiClient.saveToken(token)
                                onSuccess()
                                return@launch
                            }
                        }
                        _isRegisterMode.value = false
                        _error.value = "Registration successful! Please log in with your credentials."
                    } else {
                        val errBody = regResponse.errorBody()?.string()
                        _error.value = ApiClient.parseError(errBody, regResponse.code(), isRegister = true)
                    }
                } else {
                    val response = ApiClient.apiService.login(LoginRequest(cleanUsername, cleanPassword))
                    if (response.isSuccessful) {
                        val body = response.body()
                        val token = body?.token
                        if (!token.isNullOrEmpty()) {
                            ApiClient.saveToken(token)
                            onSuccess()
                        } else if (body?.error != null || body?.message != null) {
                            _error.value = body.error ?: body.message
                        } else {
                            _error.value = "Login failed: No authentication token received."
                        }
                    } else {
                        val errBody = response.errorBody()?.string()
                        _error.value = ApiClient.parseError(errBody, response.code(), isRegister = false)
                    }
                }
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Auth error", e)
                val msg = e.localizedMessage
                if (msg != null && (msg.contains("401") || msg.contains("Unauthorized"))) {
                    _error.value = "Invalid username or password. If you don't have an account, tap Register below."
                } else {
                    _error.value = e.localizedMessage ?: "Network connection error. Please try again."
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(navController: NavController, viewModel: LoginViewModel = viewModel()) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(true) }
    
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isRegisterMode by viewModel.isRegisterMode.collectAsState()

    Scaffold(containerColor = BackgroundDark) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isRegisterMode) "Create Account" else "Welcome Back",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isRegisterMode) "Sign up to start organizing your cloud storage" else "Log in to access your linked drives",
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    viewModel.clearError()
                },
                label = { Text("Username") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = "Username", tint = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = SurfaceDark,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    cursorColor = AccentBlue,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    viewModel.clearError()
                },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "Password", tint = TextSecondary) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        val icon = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                        Icon(icon, contentDescription = "Toggle Password Visibility", tint = TextSecondary)
                    }
                },
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = SurfaceDark,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    cursorColor = AccentBlue,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberMe,
                        onCheckedChange = { rememberMe = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentBlue,
                            uncheckedColor = TextSecondary,
                            checkmarkColor = Color.White
                        )
                    )
                    Text("Remember me", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            
            if (error != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    viewModel.authenticate(username, password) {
                        navController.navigate("main") {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(AccentBlue, AccentSky)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                enabled = !isLoading && username.isNotBlank() && password.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        if (isRegisterMode) "Sign Up" else "Log In",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isRegisterMode) "Already have an account? " else "No account yet? ",
                    color = TextSecondary
                )
                Text(
                    text = if (isRegisterMode) "Log In" else "Register",
                    color = AccentSky,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { viewModel.toggleMode() }
                )
            }
        }
    }
}
