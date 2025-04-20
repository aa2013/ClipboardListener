@echo off
set ANDROID_JAR=D:/Env/Android/Sdk/platforms/android-35/android.jar
set D8_TOOL=D:\Env\Android\Sdk\build-tools\35.0.0\d8
javac -encoding UTF-8 -d ../build -cp %ANDROID_JAR% -sourcepath . ../src/android/content/*.java ../src/top/coclyun/clipshare/clipboard_listener/*.java
%D8_TOOL% --lib %ANDROID_JAR% ../build/top/coclyun/clipshare/clipboard_listener/*.class --output ../../src/main/assets/listener.zip
echo finished
