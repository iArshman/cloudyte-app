        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename File", color = TextPrimary) },
                text = {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                },
                containerColor = SurfaceDark,
                confirmButton = {
                    TextButton(onClick = {
                        if (newFileName.isNotBlank()) {
                            viewModel.renameFile(accountId, nonNullFile.effectiveId, newFileName) {
                                showRenameDialog = false
                            }
                        }
                    }) {
                        Text("Save", color = PurpleGradientStart)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }
