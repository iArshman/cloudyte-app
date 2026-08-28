import re

with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'r') as f:
    content = f.read()

# 1. We need to extract the Account / Drive Avatar Box
avatar_match = re.search(r'(// Account / Drive Avatar Style Button\s+Box \{.*?\n\s+\}\n\s+\})', content, re.DOTALL)
if avatar_match:
    avatar_code = avatar_match.group(1)
    
    # Remove it from its original place
    content = content.replace(avatar_code, '')
    
    # Insert it before the Search Bar
    search_bar_start = content.find('// Search Bar')
    content = content[:search_bar_start] + avatar_code + '\n                ' + content[search_bar_start:]

# 2. We need to combine Sort and Filter, and remove Sort Dropdown Button
sort_match = re.search(r'(// Sort Dropdown Button\s+Box \{.*?\n\s+\}\n\s+\})', content, re.DOTALL)
if sort_match:
    sort_code = sort_match.group(1)
    # Remove sort button entirely
    content = content.replace(sort_code, '')

# Let's add the sort options to the filter dropdown menu!
# Find the filter dropdown menu options
filter_menu_target = 'val filterOptions = listOf('
if filter_menu_target in content:
    sort_menu_items = """                        Text(
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
"""
    content = content.replace(filter_menu_target, sort_menu_items + '                        ' + filter_menu_target)


with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'w') as f:
    f.write(content)
