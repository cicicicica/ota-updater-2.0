#!/bin/bash
# Copyright (C) 2012 OTA Update Center
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

UPDATE_API_URL="https://www.otaupdatecenter.pro/pages/update_api.php"
SC_VER="1.0-RC1"

help=0
if [[ $# == 0 ]] ; then
  help=1
fi

opttmp=`getopt -o hu:r:v:t:f:m:c:x:d:l:k: --long \
help,userid:,romid:,version:,otatime:,\
file:,md5:,changelog:,changelogfile:,\
device:,url:,key:verbose -- "$@"`

if [[ $? != 0 ]] ; then echo "INPUT ERROR! Terminating" >&2 ; exit 1 ; fi

eval set -- "$opttmp"

USER_ID=-1
ROM_ID=-1
DEVICE=-1
VERSION=-1
OTATIME=-1
file=-1
MD5=-1
CHANGELOG=-1
changelog_file=-1
verbose=0
URL=-1
KEY="$HOME/.ssh/id_rsa"

while true; do
  case "$1" in
    -u | --userid )        USER_ID="$2";        shift 2 ;;
    -r | --romid )         ROM_ID="$2";         shift 2 ;;
    -d | --device )        DEVICE="$2";         shift 2 ;;
    -v | --version )       VERSION="$2";        shift 2 ;;
    -t | --otatime )       OTATIME="$2";        shift 2 ;;
    -f | --file )          file="$2";           shift 2 ;;
    -m | --md5 )           MD5="$2";            shift 2 ;;
    -c | --changelog )     CHANGELOG="$2";      shift 2 ;;
    -x | --changelogfile ) changelog_file="$2"; shift 2 ;;
    -l | --url )           URL="$2";            shift 2 ;;
    -k | --key )           KEY="$2";            shift 2 ;;
    --verbose )            verbose=1;           shift   ;;

    -h | --help )          help=1;              shift ; break ;;

    -- ) shift; break ;;
    * ) break ;;
  esac
done

if [[ $help == 1 ]] ; then
  echo
  echo "+==============================================================+"
  echo "|      *** OTA Update Center - Auto Update Script HELP ***     |"
  echo "+==============================================================+"
  echo "| Usage: update_rominfo.sh [-h|--help]  -  Print this          |"
  echo "|       update_rominfo.sh {-u|--userid} <user id>              |"
  echo "|                         {-r|--romid} <rom id>                |"
  echo "|                         {-d|--device} <device>               |"
  echo "|                         {-l|--url} <download URL>            |"
  echo "|                         {-f <update.zip file>|-m <md5>}      |"
  echo "|                         [{-t|--otatime} <ota date/time>]     |"
  echo "|                         [{-v|--version} <ota version>]       |"
  echo "|                         [{-c <changelog>|-x <changelogfile>}]|"
  echo "|                         [{-k|--key} <key file>]              |"
  echo "|                         [--verbose]                          |"
  echo "+--------------------------------------------------------------+"
  echo "| <user id>       - numeric user ID on the website             |"
  echo "| <rom id>        - numeric ROM ID on the website              |"
  echo "| <device>        - device name (ro.product.device)            |"
  echo "| <download URL>  - URL where the update.zip is hosted         |"
  echo "| <.zip file>     - update.zip file to use for getting MD5-sum |"
  echo "| <md5>           - specify MD5 instead of calculating         |"
  echo "| <ota date/time> - date/time of update, yyyymmdd-hhmm format  |"
  echo "|                   if unspecified, current date/time is used  |"
  echo "| <ota version>   - version of ota update                      |"
  echo "|                   if unspecified, current version is used    |"
  echo "| <changelog>     - changelog for update (be sure to quote)    |"
  echo "| <changelog file>- file to read changelog from                |"
  echo "|                   if unspecified, blank changelog is used    |"
  echo "| <key file>      - private key file to use                    |"
  echo "|                   if unspecified, ~/.ssh/id_rsa is used      |"
  echo "+==============================================================+"
  exit 0
fi

if [[ $USER_ID == -1 ]] ; then
  echo "User ID not specified! Terminating" >&2
  exit 2
fi

if [[ $ROM_ID == -1 ]] ; then
  echo "ROM ID not specified! Terminating" >&2
  exit 3
fi

if [[ $DEVICE == -1 ]] ; then
  echo "Device not specified! Terminating" >&2
  exit 4
fi

if [[ $URL == -1 ]] ; then
  echo "Download URL not specified! Terminating" >&2
  exit 5
fi

if [[ $file == -1 && $MD5 == -1 ]] ; then
  echo "Neither file path nor file md5 specified! Terminating" >&2
  exit 6
elif [[ $file != -1 && $MD5 != -1 && $verbose == 1 ]]; then
  echo "WARNING: both file and MD5 specified, computed MD5 will be used"
fi

if [[ $file != -1 ]] ; then
  echo -n "Computing MD5 ... "
  MD5=`md5sum "$file" | awk '{ print $1 }'`
  echo "DONE: $MD5"
fi

if [[ $VERSION == -1 ]] ; then
  VERSION=""
  if [[ $verbose == 1 ]] ; then
    echo "WARNING: version not specified, will not update version!"
  fi
fi

if [[ $OTATIME == -1 ]] ; then
  OTATIME=`date +%Y%m%d-%k%M`
  if [[ $verbose == 1 ]] ; then
    echo "WARNING: ota time not specified, using $OTATIME"
  fi
fi

if [[ $CHANGELOG == -1 && $changelog_file == -1 ]] ; then
  CHANGELOG=""
  if [[ $verbose == 1 ]] ; then
    echo "WARNING: changelog not specified, using blank changelog"
  fi
elif [[ $CHANGELOG != -1 && $changelog_file != -1 ]] ; then
  echo "WARNING: both changelog text and file specified, file will be used"
fi

if [[ $changelog_file != -1 ]] ; then
  CHANGELOG=`cat "$changelog_file"`
fi

VERSION=`echo -n "$VERSION" | tr '"' '\"'`
URL=`echo -n "$URL" | tr '"' '\"'`
CHANGELOG=`echo -n "$CHANGELOG" | tr '"' '\"'`

data_str="{\"user_id\":$USER_ID,\
\"rom_id\":$ROM_ID,\
\"device\":\"$DEVICE\",\
\"version\":\"$VERSION\",\
\"otatime\":\"$OTATIME\",\
\"url\":\"$URL\",\
\"md5\":\"$MD5\",\
\"changelog\":\"$CHANGELOG\"}"

sig=`echo -n "$data_str" | \
     openssl sha1 -binary | \
     openssl rsautl -sign -inkey "$KEY" | \
     openssl enc -base64 | \
     tr -d '\n'`

esc_data_str=`echo -n "$data_str" | sed 's/"/\\\"/g'`
payload="{\"data\":\"$esc_data_str\",\"sig\":\"$sig\"}"

#echo $payload

curl --data "$payload" -A "OTA Update Center Upload Script v$SC_VER" $UPDATE_API_URL
