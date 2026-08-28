import re

with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'r') as f:
    lines = f.readlines()

start_idx = -1
end_idx = -1

for i, line in enumerate(lines):
    if '// High-End Google Drive / OneDrive Style Top Bar' in line:
        start_idx = i
    if '// Active Filter Pill indicator if not "All"' in line:
        end_idx = i - 1
        break

if start_idx != -1 and end_idx != -1:
    new_topbar = """            // High-End Google Drive / OneDrive Style Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Account / Drive Avatar Style Button
                Box {
                    val providerColor = when {
                        selectedAccount?.effectiveType?.contains("google", ignoreCase = true) == true -> Color(0xFF4285F4)
                        selectedAccount?.effectiveType?.contains("one", ignoreCase = true) == true -> Color(0xFF0078D4)
                        selectedAccount?.effectiveType?.contains("tele", ignoreCase = true) == true -> Color(0xFF24A1DE)
                        else -> AccentBlue
                    }
                    val initialLetter = (selectedAccount?.effectiveName?.firstOrNull() ?: 'C').uppercaseChar().toString()
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(providerColor.copy(alpha = 0.2f))
                            .border(1.5.dp, providerColor, CircleShape)
                            .clickable { showAccountDropdown = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initialLetter,
                            color = providerColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    DropdownMenu(
                        expanded = showAccountDropdown,
                        onDismissRequest = { showAccountDropdown = false },
                        modifier = Modifier
                            .background(SurfaceDark)
                            .widthIn(min = 270.dp)
                    ) {
                        Text(
                            text = "Connected Cloud Drives",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(color = Color(0xFF2C2C3E))
                        accounts.forEach { acc ->
                            val isSelected = acc.effectiveId == currentAccountId
                            val driveColor = when {
                                acc.effectiveType.contains("google", ignoreCase = true) -> Color(0xFF4285F4)
                                acc.effectiveType.contains("one", ignoreCase = true) -> Color(0xFF0078D4)
                                else -> Color(0xFF24A1DE)
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CloudQueue,
                                            contentDescription = null,
                                            tint = if (isSelected) AccentBlue else driveColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = acc.effectiveName,
                                                color = if (isSelected) AccentBlue else TextPrimary,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = acc.effectiveType,
                                                color = TextSecondary.copy(alpha = 0.7f),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                },
                                trailingIcon = {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Active",
                                            tint = AccentBlue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.selectAccount(acc.effectiveId)
                                    showAccountDropdown = false
                                }
                            )
                        }
                        HorizontalDivider(color = Color(0xFF2C2C3E))
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = AccentBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Link New Cloud Drive...",
                                        color = AccentBlue,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }
                            },
                            onClick = {
                                showAccountDropdown = false
                                showAddAccountDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Manage Accounts in Settings",
                                        color = TextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            },
                            onClick = {
                                showAccountDropdown = false
                                navController.navigate("accounts")
                            }
                        )
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = if (selectedAccount != null) "Search in ${selectedAccount.effectiveName}..." else "Search files...",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, tint = TextSecondary, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        } else {
                            IconButton(onClick = { viewModel.loadFiles() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = SurfaceDark,
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )
                
                // Sort & Filter Dropdown Icon Button
                Box {
                    IconButton(
                        onClick = { showFilterDropdown = true },
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                if (selectedFilter != "All" || sortOrder != FileSortOrder.NAME_ASC) AccentBlue.copy(alpha = 0.18f) else SurfaceDark,
                                CircleShape
                            )
                    ) {
                        BadgedBox(
                            badge = {
                                if (selectedFilter != "All") {
                                    Badge(
                                        containerColor = AccentBlue,
                                        modifier = Modifier.size(8.dp)
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Sort and Filter files",
                                tint = if (selectedFilter != "All" || sortOrder != FileSortOrder.NAME_ASC) AccentBlue else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showFilterDropdown,
                        onDismissRequest = { showFilterDropdown = false },
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        Text(
                            text = "Sort Files By",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        FileSortOrder.values().forEach { order ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = order.displayName,
                                        color = if (sortOrder == order) AccentBlue else TextPrimary,
                                        fontWeight = if (sortOrder == order) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                },
                                trailingIcon = {
                                    if (sortOrder == order) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                                    }
                                },
                                onClick = {
                                    sortOrder = order
                                    showFilterDropdown = false
                                }
                            )
                        }
                        HorizontalDivider(color = Color(0xFF2C2C3E))
                        Text(
                            text = "Filter Files By",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        val filterOptions = listOf(
                            Triple("All", "All Files", Icons.Default.FolderOpen),
                            Triple("Offline", "Offline Available", Icons.Default.CheckCircle),
                            Triple("Folders", "Folders Only", Icons.Default.Folder),
                            Triple("Images", "Photos & Images", Icons.Default.Image),
                            Triple("Videos", "Videos & Movies", Icons.Default.Movie),
                            Triple("Audio", "Audio & Music", Icons.Default.MusicNote),
                            Triple("Docs", "Documents & Code", Icons.Default.Description)
                        )
                        filterOptions.forEach { (filterKey, filterLabel, filterIcon) ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = filterIcon,
                                            contentDescription = null,
                                            tint = if (selectedFilter == filterKey) AccentBlue else TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = filterLabel,
                                            color = if (selectedFilter == filterKey) AccentBlue else TextPrimary,
                                            fontWeight = if (selectedFilter == filterKey) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 14.sp
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (selectedFilter == filterKey) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = AccentBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    selectedFilter = filterKey
                                    showFilterDropdown = false
                                }
                            )
                        }
                    }
                }

                // Layout Style Switcher (List vs Grid)
                IconButton(
                    onClick = {
                        isGridView = !isGridView
                        sharedPrefs.edit().putBoolean("is_grid_view", isGridView).apply()
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .background(SurfaceDark, CircleShape)
                ) {
                    Icon(
                        imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                        contentDescription = if (isGridView) "Switch to List View" else "Switch to Grid View",
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
"""

    lines = lines[:start_idx] + [new_topbar] + lines[end_idx:]
    with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'w') as f:
        f.writelines(lines)
else:
    print(f"Failed to find indices. Start: {start_idx}, End: {end_idx}")

