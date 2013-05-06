set SERVICE_NAME=Madaap
set PR_INSTALL=C:\workspaces\blog\procrun-demo\prunsrv.exe
 
REM Service log configuration
set PR_LOGPREFIX=Madaap
set PR_LOGPATH=D:\madaap\logs
set PR_STDOUTPUT=D:\madaap\stdout.txt
set PR_STDERROR=D:\madaap\stderr.txt
set PR_LOGLEVEL=Error
 
REM Path to java installation
set PR_JVM=C:\Program Files (x86)\Java\jre7\bin\client\jvm.dll
set PR_CLASSPATH=D:\madaap\madaap.jar
 
REM Startup configuration
set PR_STARTUP=auto
set PR_STARTMODE=jvm
set PR_STARTCLASS=extractor.Madaap
set PR_STARTMETHOD=start
 
REM Shutdown configuration
set PR_STOPMODE=jvm
set PR_STOPCLASS=extractor.Madaap
set PR_STOPMETHOD=stop
 
REM JVM configuration
set PR_JVMMS=256
set PR_JVMMX=1024
set PR_JVMSS=4000
 
REM Install service
prunsrv.exe //IS//Madaap