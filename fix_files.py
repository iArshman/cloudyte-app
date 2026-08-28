import re
with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'r') as f:
    text = f.read()

# Fix the dangling leadingIcon
bad_piece = """                    ),
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = AccentBlue) },
                                onClick = {
                                    showMenu = false
                                    coroutineScope.launch {
                                        Toast.makeText(context, "Downloading for offline...", Toast.LENGTH_SHORT).show()
                                        val res = OfflineFileManager.downloadFile(context, accountId, file)
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Saved offline!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }"""
text = text.replace(bad_piece, """                    )""")

bad_piece_2 = """                    ),
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = AccentBlue) },
                                onClick = {
                                    showMenu = false
                                    coroutineScope.launch {
                                        Toast.makeText(context, "Downloading for offline...", Toast.LENGTH_SHORT).show()
                                        val res = OfflineFileManager.downloadFile(context, accountId, file)
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Saved offline!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }"""
text = text.replace(bad_piece_2, """                    )""")

# Wait, the regex might be slightly different. Let's use a regex to match it.
text = re.sub(r',\s*leadingIcon = \{ Icon\(Icons\.Default\.Download.*?\}\s*\)\s*\}\s*\}', '', text, flags=re.DOTALL)
text = re.sub(r'downloadedFiles\.contains\([^\)]*\)', 'false', text)
text = re.sub(r'OfflineFileManager\.[a-zA-Z]+\([^\)]*\)', 'false', text)

with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'w') as f:
    f.write(text)
