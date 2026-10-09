Stream4k60 debug APKs (signed with the project debug key, install over each other with adb install -r)

1-current-main-19ccf36/Stream4k60-main-19ccf36.apk
    Same code as the main branch: USB Resolution / Frame rate / Format dropdowns with Custom values,
    real USB link-speed detection, usb-devices.txt report, H.264 webcam fixes, software MJPEG decoding.

2-colour-fix-4f8c20d/Stream4k60-colour-fix-4f8c20d.apk
    Everything in 1, plus the washed-out colour fix: the stream is encoded and labelled as standard
    HD colour (BT.709, limited range) like OBS, and capture-card frames at 720p+ use HD colour conversion.
    Keep HDR off.

Install:  adb install -r builds\2-colour-fix-4f8c20d\Stream4k60-colour-fix-4f8c20d.apk
