#!/bin/bash
#      MIT License
#
#      Copyright (c) 2020 Bjoern Beier
#
#      Permission is hereby granted, free of charge, to any person obtaining a copy
#      of this software and associated documentation files (the "Software"), to deal
#      in the Software without restriction, including without limitation the rights
#      to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
#      copies of the Software, and to permit persons to whom the Software is
#      furnished to do so, subject to the following conditions:
#
#      The above copyright notice and this permission notice shall be included in all
#      copies or substantial portions of the Software.
#
#      THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
#      IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
#      FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
#      AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
#      LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
#      OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
#      SOFTWARE.

JMSDUDE_VERSION="v1.0.0"
JMSDUDE_DOWNLOAD="https://github.com/begris/test/releases/download/${JMSDUDE_VERSION}/jms-dude-${JMSDUDE_VERSION}.zip"

if [ -z "$JMSDUDE_HOME" ]; then
    JMSDUDE_HOME="$HOME/.jms-dude"
fi

dude_bin_folder="${JMSDUDE_HOME}/bin"
dude_tmp_folder="${JMSDUDE_HOME}/tmp"
dude_zip_file="${dude_tmp_folder}/jms-dude-${JMSDUDE_VERSION}.zip"
dude_release_folder="${JMSDUDE_HOME}/${JMSDUDE_VERSION}"
dude_script_file="${dude_bin_folder}/jms-dude.groovy"
dude_command="${dude_bin_folder}/jms-dude"
dude_init="${dude_bin_folder}/jms-dude-init.sh"
dude_completion="${dude_bin_folder}/jms-dude_completion.sh"
dude_bash_profile="${HOME}/.bash_profile"
dude_profile="${HOME}/.profile"
dude_bashrc="${HOME}/.bashrc"
dude_zshrc="${HOME}/.zshrc"


dude_init_snippet=$( cat << EOF
export JMSDUDE_HOME="$JMSDUDE_HOME"
source "${dude_init}"
EOF
)

dude_init_script=$( cat << EOF
export JMSDUDE_HOME="$JMSDUDE_HOME"
export PATH="${dude_bin_folder}:$PATH"
source "${dude_completion}"
EOF
)

# OS specific support (must be 'true' or 'false').
cygwin=false;
darwin=false;
solaris=false;
freebsd=false;
case "$(uname)" in
    CYGWIN*)
        cygwin=true
        ;;
    Darwin*)
        darwin=true
        ;;
    SunOS*)
        solaris=true
        ;;
    FreeBSD*)
        freebsd=true
esac


echo ''
echo "      _                 __        __"
echo "     (_)_ _  __________/ /_ _____/ /__"
echo "    / /  \' \(_-<___/ _  / // / _  / -_)"
echo " __/ /_/_/_/___/   \_,_/\_,_/\_,_/\__/"
echo "|___/"
echo ''
echo '                     ...starting installation...'
echo ''
echo ''


echo "Looking for unzip..."
if [ -z $(which unzip) ]; then
	echo "Not found."
	echo "======================================================================================================"
	echo " Please install unzip on your system using your favourite package manager."
	echo ""
	echo " Restart after installing unzip."
	echo "======================================================================================================"
	echo ""
	exit 1
fi

echo "Looking for curl..."
if [ -z $(which curl) ]; then
	echo "Not found."
	echo ""
	echo "======================================================================================================"
	echo " Please install curl on your system using your favourite package manager."
	echo ""
	echo " Restart after installing curl."
	echo "======================================================================================================"
	echo ""
	exit 1
fi

echo "Looking for groovy..."
if [ -z $(which groovy) ]; then
	echo "Not found."
	echo ""
	echo "======================================================================================================"
	echo " Please install groovy on your system using your favourite package manager."
	echo ""
	echo " Restart after installing groovy."
	echo "======================================================================================================"
	echo ""
	exit 1
fi

echo "Installing jms-dude version ${JMSDUE_VERSION}"

echo "Create distribution directories..."
mkdir -p "$dude_tmp_folder"

echo "Download installation archive..."
curl --location --progress-bar "${JMSDUDE_DOWNLOAD}" > "$dude_zip_file"

ARCHIVE_OK=$(unzip -qt "$dude_zip_file" | grep 'No errors detected in compressed data')
if [[ -z "$ARCHIVE_OK" ]]; then
	echo "Downloaded zip archive corrupt. Are you connected to the internet?"
	echo ""
	echo "* cleaning up installation dir"
	rm -rf "$JMSDUDE_HOME"
	exit 2
fi

echo "Extract script archive..."
if [[ "$cygwin" == 'true' ]]; then
	echo "Cygwin detected - normalizing paths for unzip..."
	dude_zip_file=$(cygpath -w "$dude_zip_file")
	dude_release_folder=$(cygpath -w "$dude_release_folder")
fi
unzip -qo "$dude_zip_file" -d "$dude_release_folder"

echo "Install scripts..."
unlink "$dude_bin_folder"
ln -s "$dude_release_folder" "$dude_bin_folder"
chmod +x "$dude_script_file"
ln -s "$dude_script_file" "$dude_command"

echo "Generate init script..."
echo -e "\n$dude_init_script" > "$dude_init"


echo "Generate bash-completion script for version ${JMSDUDE_VERSION}..."
groovy "$dude_script_file" "--auto-completion" >> "$dude_completion"

if [[ $darwin == true ]]; then
  touch "$dude_bash_profile"
  echo "Attempt update of login bash profile on OSX..."
  if [[ -z $(grep 'JMSDUDE_HOME' "$dude_bash_profile") ]]; then
    echo -e "\n$dude_init_snippet" >> "$dude_bash_profile"
    echo "Added jms-dude init snippet to $dude_bash_profile"
  fi
else
  echo "Attempt update of interactive bash profile on regular UNIX..."
  touch "${dude_bashrc}"
  if [[ -z $(grep 'JMSDUDE_HOME' "$dude_bashrc") ]]; then
      echo -e "\n$dude_init_snippet" >> "$dude_bashrc"
      echo "Added jms-dude init snippet to $dude_bashrc"
  fi
fi

echo "Attempt update of zsh profile..."
touch "$dude_zshrc"
if [[ -z $(grep 'JMSDUDE_HOME' "$dude_zshrc") ]]; then
    echo -e "\n$dude_init_snippet" >> "$dude_zshrc"
    echo "Added jms-dude init snippet to ${dude_zshrc}"
fi

echo -e "\n\n\nAll done!\n\n"

echo "Please open a new terminal, or run the following in the existing one:"
echo ""
echo "    source \"${JMSDUDE_HOMEDIR}/bin/jms-dude-init.sh\""
echo ""
echo "Then issue the following command:"
echo ""
echo "    jms-dude --help"
echo ""
echo "Enjoy jms-dude!!!"

