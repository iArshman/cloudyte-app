import os
import re

files_to_check = [
    'app/src/main/java/com/example/ui/screens/AddAccountDialog.kt',
    'app/src/main/java/com/example/ui/screens/VideoPlayerScreen.kt',
    'app/src/main/java/com/example/ui/screens/TextEditorScreen.kt',
    'app/src/main/java/com/example/ui/screens/PdfViewerScreen.kt',
    'app/src/main/java/com/example/ui/screens/FileDetailScreen.kt',
    'app/src/main/java/com/example/ui/screens/AudioPlayerScreen.kt',
    'app/src/main/java/com/example/ui/screens/ImageViewerScreen.kt'
]

for file_path in files_to_check:
    with open(file_path, 'r') as f:
        content = f.read()

    # We need to make sure context and ApiClient are available where we replace.
    # A generic replacement strategy: find where Intent.ACTION_VIEW is used and replace the intent creation + startActivity
    
    # Example snippet: 
    # val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl))
    # context.startActivity(intent)
    
    # Or:
    # val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
    #     flags = Intent.FLAG_ACTIVITY_NEW_TASK
    # }
    # context.startActivity(intent)

    if 'Intent.ACTION_VIEW' in content:
        # First add import for DownloadHelper if not present
        if 'import com.example.api.DownloadHelper' not in content:
            content = content.replace('import android.content.Intent', 'import android.content.Intent\nimport com.example.api.DownloadHelper')
        
        # We can just replace the block.
        # But this is tricky because the variable name (videoUrl, downloadUrl, pdfUrl, currentFileUrl, fileUrl) varies.
        # Let's use regex to find:
        # val intent = Intent(Intent.ACTION_VIEW, Uri.parse( *([^)]+) *))(?:\.apply\s*\{\s*[^\}]*\s*\})?
        # \s*context\.startActivity\(intent\)
        
        pattern = re.compile(r'val\s+intent\s*=\s*Intent\(Intent\.ACTION_VIEW,\s*Uri\.parse\(([^)]+)\)\)(?:\.apply\s*\{[^}]*\})?\s*context\.startActivity\(intent\)')
        
        def replacer(match):
            url_var = match.group(1)
            # Find file name somehow. In these screens, 'file' or 'currentFile' is usually available.
            # But just "download" works as a fallback name if not found.
            # Usually `file.effectiveName` or `currentFile.effectiveName` is in scope.
            # Let's use "downloaded_file" as a fallback, but actually we can check context.
            name_var = 'file.effectiveName' if 'file.effectiveName' in content else '"downloaded_file"'
            if 'currentFile?.effectiveName' in content:
                name_var = 'currentFile?.effectiveName ?: "download"'
            elif 'currentFile.effectiveName' in content:
                name_var = 'currentFile.effectiveName'
            
            # Since these files are viewer screens, `file.effectiveName` is usually available for the specific file passed.
            return f'DownloadHelper.downloadFile(context, {url_var}, {name_var}, com.example.api.ApiClient.getToken())'

        content = pattern.sub(replacer, content)
        
        with open(file_path, 'w') as f:
            f.write(content)

