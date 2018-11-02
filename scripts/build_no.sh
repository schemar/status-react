#!/usr/bin/env bash

set -e

#####################################################################
#
# This script manages app build numbers.
# It returns the next build number to be used.
# The limit of size of the number is signed int, which is 2147483647.
#
# These numbers are used to mark app artifacts for:
# * Play Store - versionCode attribute (gradle)
# * Apple Store - CFBundleVersion attribute (plutil)
#
# For more details see:
# * https://developer.android.com/studio/publish/versioning
# * https://developer.apple.com/library/content/documentation/General/Reference/InfoPlistKeyReference/Articles/CoreFoundationKeys.html
#
# History: This script used to tag builds with `build-[0-9]+` tags.
#
#####################################################################

REPO_ROOT=$(git rev-parse --show-toplevel)
VERSION_REGEX='([0-9]+).([0-9]+).([0-9]+)'

getNumber() {
    YEAR_AND_DAYS=$(date '+%y%m') # Year + Month
    VERSION=$(cat ${REPO_ROOT}/mobile_files/VERSION)
    if [[ ${VERSION} =~ ${VERSION_REGEX} ]]; then
        MAJOR_VER=$(printf "%02d" "${BASH_REMATCH[1]}")
        MINOR_VER=$(printf "%02d" "${BASH_REMATCH[2]}")
        REVIS_VER=$(printf "%03d" "${BASH_REMATCH[3]}")
        echo "${MAJOR_VER}${MINOR_VER}${REVIS_VER}${YEAR_AND_DAYS}"
    else
        echo "Unable to parse version number: ${VERSION}"
        exit 1
    fi
}

#####################################################################
# This should result in numbers like: 00090301811 or 01090301811

getNumber
