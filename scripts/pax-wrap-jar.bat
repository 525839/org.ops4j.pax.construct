@echo off
SETLOCAL
set _SCRIPTS_=%~dp0
call "%_SCRIPTS_%\pax-validate"





REM while
REM   getopts g:a:v:h- SETTING
REM do
REM   case ${SETTING} in
REM     h|\?) echo "pax-wrap-jar -g groupId -a artifactId -v version [-- mvnopts ...]" ; exit ;;
REM 
REM     g) GROUPID=${OPTARG} ;;
REM     a) ARTIFACTID=${OPTARG} ;;
REM     v) VERSION=${OPTARG} ;;
REM 
REM     -) break ;;
REM   esac
REM done
REM 
REM shift $((${OPTIND}-1))





set _EXTRA_=%*

if ""=="%_GROUPID_%" set /p _GROUPID_="library groupId (org.ops4j.example) ? "
if ""=="%_ARTIFACTID_%" set /p _ARTIFACTID_="library artifactId (myJarFile) ? "
if ""=="%_VERSION_%" set /p _VERSION_="library version (0.1.0-SNAPSHOT) ? "

if ""=="%_GROUPID_%" set _GROUPID_=org.ops4j.example
if ""=="%_ARTIFACTID_%" set _ARTIFACTID_=myJarFile
if ""=="%_VERSION_%" set _VERSION_=0.1.0-SNAPSHOT

@echo on
echo mvn pax:wrap-jar -DgroupId=%_GROUPID_% -DartifactId=%_ARTIFACTID_% -Dversion=%_VERSION_% %_EXTRA_%