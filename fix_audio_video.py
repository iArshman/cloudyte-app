import re

for filename in ['AudioPlayerScreen.kt', 'VideoPlayerScreen.kt']:
    path = f'app/src/main/java/com/example/ui/screens/{filename}'
    with open(path, 'r') as f:
        text = f.read()

    # AudioPlayerScreen errors were:
    # e: file:///app/applet/app/src/main/java/com/example/ui/screens/AudioPlayerScreen.kt:376:29 Unresolved reference 'isPlaying'.
    # This means `exoPlayer` wasn't created properly.

    text = re.sub(r'val initialUrl = .*?MediaItem\.fromUri\(audioUrl\)', 'val initialMediaItem = MediaItem.fromUri(audioUrl)', text, flags=re.DOTALL)
    text = re.sub(r'val initialUrl = .*?MediaItem\.fromUri\(initialUrl\)', 'val initialMediaItem = MediaItem.fromUri(audioUrl)', text, flags=re.DOTALL)
    
    with open(path, 'w') as f:
        f.write(text)

