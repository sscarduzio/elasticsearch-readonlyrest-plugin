#!/bin/bash -e

usage()
{
    cat <<USAGE
##########################################################################
Originally adapted from https://www.aws.ps/how-to-upload-file-to-s3-using-curl
(since modified for ROR: custom S3_ENDPOINT_URL, SigV4 date-scope fix, no ACL/MD5).

Simple script uploading a file to S3 (or any S3-compatible endpoint). Supports AWS
signature version 4, custom region, a custom endpoint, and mime-types.

Usage:
  `basename $0` aws_ak aws_sk bucket srcfile targfile [mime_type]

Where <arg> is one of:
  aws_ak     access key ('' for upload to public writable bucket)
  aws_sk     secret key ('' for upload to public writable bucket)
  bucket     bucket name (with optional @region suffix, default is us-east-1)
  srcfile    path to source file
  targfile   path to target (dir if it ends with '/', relative to bucket root)
  mime_type  optional mime-type (tries to guess if omitted)

Optional environment variables:
  S3_ENDPOINT_URL  custom S3-compatible endpoint (uses path-style addressing).
                   Defaults to https://<bucket>.s3.amazonaws.com/ when unset.

Dependencies:
  To run, this shell script depends on command-line curl and openssl, as well
  as standard Unix tools

Examples:
  To upload file '~/blog/media/image.png' to bucket 'storage' in region
  'eu-central-1' with key (path relative to bucket) 'media/image.png':

\$  `basename $0` ACCESS SECRET storage@eu-central-1  ~/blog/image.png media/

  To upload file '~/blog/media/image.png' to public-writable bucket 'storage'
  in default region 'us-east-1' with key (path relative to bucket) 'x/y.png':

    `basename $0` '' '' storage ~/blog/image.png x/y.png

USAGE
    exit 0
}

guessmime()
{
    mime=`file -b --mime-type $1`
    if [ "$mime" = "text/plain" ]; then
        case $1 in
            *.zip)           mime=application/zip;;
            *.sh)            mime=text/plain;;
            *.sha1)          mime=text/plain;;
            *.md)            mime=text/plain;;
            *.json)          mime=application/json;;
            *.css)           mime=text/css;;
            *.ttf|*.otf)     mime=application/font-sfnt;;
            *.woff)          mime=application/font-woff;;
            *.woff2)         mime=font/woff2;;
            *rss*.xml|*.rss) mime=application/rss+xml;;
            *)               if head $1 | grep '<html.*>' >/dev/null; then mime=text/html; fi;;
        esac
    fi
    printf "$mime"
}

if [ $# -lt 5 ]; then usage; fi

# Inputs.
aws_ak="$1"                                     # access key
aws_sk="$2"                                     # secret key
bucket=`printf $3 | awk 'BEGIN{FS="@"}{print $1}'`                       # bucket name
region=`printf $3 | awk 'BEGIN{FS="@"}{print ($2==""?"us-east-1":$2)}'`  # region name
srcfile="$4"                                                             # source file
targfile=`echo -n "$5" | sed "s/\/$/\/$(basename $srcfile)/"`            # target file
mime=${6:-"`guessmime "$srcfile"`"}                                      # mime type


# Create signature if not public upload.
key_and_sig_args=''
if [ "$aws_ak" != "" ] && [ "$aws_sk" != "" ]; then

    # SigV4 requires the credential scope date to match the request date.
    # `today_s` is used for both signing and the X-Amz-Credential field.
    # `expdate` is the policy expiration (tomorrow), unrelated to the credential date.
    date=`date -u +%Y%m%dT%H%M%SZ`
    today_s=`date -u +%Y%m%d`
    expdate=`if ! date -v+1d +%Y-%m-%d 2>/dev/null; then date -d tomorrow +%Y-%m-%d; fi`
    service='s3'

    # Generate policy and sign with secret key following AWS Signature version 4, below
    # -A suppresses base64 line wrapping (some S3-compatible proxies reject wrapped policies).
    p=$(cat <<POLICY | openssl base64 -A
{ "expiration": "${expdate}T12:00:00.000Z",
  "conditions": [
    {"bucket": "$bucket" },
    ["starts-with", "\$key", ""],
    ["starts-with", "\$content-type", ""],
    ["content-length-range", 1, `ls -l -H "$srcfile" | awk '{print $5}' | head -1`],
    {"x-amz-date": "$date" },
    {"x-amz-credential": "$aws_ak/$today_s/$region/$service/aws4_request" },
    {"x-amz-algorithm": "AWS4-HMAC-SHA256" }
  ]
}
POLICY
    )

    # AWS4-HMAC-SHA256 signature.
    # `awk '{print $NF}'` extracts just the hex digest from openssl output, which
    # may be either "(stdin)= <hex>" (LibreSSL) or "SHA2-256(stdin)= <hex>" (OpenSSL 3+).
    s=`printf "$today_s"     | openssl sha256 -hmac "AWS4$aws_sk"           -hex | awk '{print $NF}'`
    s=`printf "$region"      | openssl sha256 -mac HMAC -macopt hexkey:"$s" -hex | awk '{print $NF}'`
    s=`printf "$service"     | openssl sha256 -mac HMAC -macopt hexkey:"$s" -hex | awk '{print $NF}'`
    s=`printf "aws4_request" | openssl sha256 -mac HMAC -macopt hexkey:"$s" -hex | awk '{print $NF}'`
    s=`printf "$p"           | openssl sha256 -mac HMAC -macopt hexkey:"$s" -hex | awk '{print $NF}'`

    # Policy goes here (not the curl line) so it's only sent when we actually signed — an
    # anonymous upload (empty keys) leaves $p unset and must not send an empty Policy= field.
    key_and_sig_args="-F X-Amz-Credential=$aws_ak/$today_s/$region/$service/aws4_request -F X-Amz-Algorithm=AWS4-HMAC-SHA256 -F X-Amz-Signature=$s -F X-Amz-Date=${date} -F Policy=$p"
fi

# Determine upload URL.
# - If S3_ENDPOINT_URL is set (e.g. for the DGP proxy) use path-style addressing: ${endpoint}/${bucket}/
# - Otherwise default to AWS virtual-hosted-style: https://${bucket}.s3.amazonaws.com/
if [ -n "${S3_ENDPOINT_URL:-}" ]; then
    upload_url="${S3_ENDPOINT_URL%/}/${bucket}/"
else
    upload_url="https://${bucket}.s3.amazonaws.com/"
fi

# Upload. Supports anonymous upload if bucket is public-writable, and keys are set to ''.
echo "Uploading: $srcfile ($mime) to $upload_url$targfile"
# Default: `-f` makes curl exit non-zero on HTTP >=400 (so callers detect
# failures) but it also SUPPRESSES the response body. Set S3_UPLOADER_DEBUG=1
# to drop -f and print the server's error XML (for debugging 403s etc).
if [ -n "${S3_UPLOADER_DEBUG:-}" ]; then
    CURL_FAIL_FLAGS="-S"
else
    CURL_FAIL_FLAGS="-f"
fi
curl -vvv                       \
    -# -k $CURL_FAIL_FLAGS      \
    -F "key=$targfile"          \
    $key_and_sig_args           \
    -F "Content-Type=$mime"     \
    -F "file=@$srcfile"         \
    "$upload_url"
