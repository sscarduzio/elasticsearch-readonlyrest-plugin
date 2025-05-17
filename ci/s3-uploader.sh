#!/bin/bash -e

usage()
{
    cat <<USAGE
##########################################################################
Script taken from https://www.aws.ps/how-to-upload-file-to-s3-using-curl

Simple script uploading a file to S3. Supports AWS signature version 4, custom
region, permissions and mime-types. Uses Content-MD5 header to guarantee
uncorrupted file transfer.

Usage:
  `basename $0` aws_ak aws_sk bucket srcfile targfile [acl] [mime_type]

Where <arg> is one of:
  aws_ak     access key ('' for upload to public writable bucket)
  aws_sk     secret key ('' for upload to public writable bucket)
  bucket     bucket name (with optional @region suffix, default is us-east-1)
  srcfile    path to source file
  targfile   path to target (dir if it ends with '/', relative to bucket root)
  acl        s3 access permissions (default: public-read)
  mime_type  optional mime-type (tries to guess if omitted)

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
acl=${6:-'public-read'}                                                  # s3 perms
mime=${7:-"`guessmime "$srcfile"`"}                                      # mime type
md5=`openssl md5 -binary "$srcfile" | openssl base64`


# Create signature if not public upload.
key_and_sig_args=''
if [ "$aws_ak" != "" ] && [ "$aws_sk" != "" ]; then

    # Need current and file upload expiration date. Handle GNU and BSD date command style to get tomorrow's date.
    date=`date -u +%Y%m%dT%H%M%SZ`
    expdate=`if ! date -v+1d +%Y-%m-%d 2>/dev/null; then date -d tomorrow +%Y-%m-%d; fi`
    expdate_s=`printf $expdate | sed s/-//g` # without dashes, as we need both formats below
    service='s3'

    # Generate policy and sign with secret key following AWS Signature version 4, below
    p=$(cat <<POLICY | openssl base64
{ "expiration": "${expdate}T12:00:00.000Z",
  "conditions": [
    {"acl": "$acl" },
    {"bucket": "$bucket" },
    ["starts-with", "\$key", ""],
    ["starts-with", "\$content-type", ""],
    ["content-length-range", 1, `ls -l -H "$srcfile" | awk '{print $5}' | head -1`],
    {"content-md5": "$md5" },
    {"x-amz-date": "$date" },
    {"x-amz-credential": "$aws_ak/$expdate_s/$region/$service/aws4_request" },
    {"x-amz-algorithm": "AWS4-HMAC-SHA256" }
  ]
}
POLICY
    )

    # AWS4-HMAC-SHA256 signature
    s=`printf "$expdate_s"   | openssl sha256 -hmac "AWS4$aws_sk"           -hex | awk '{print $NF}'`
    s=`printf "$region"      | openssl sha256 -mac HMAC -macopt hexkey:"$s" -hex | awk '{print $NF}'`
    s=`printf "$service"     | openssl sha256 -mac HMAC -macopt hexkey:"$s" -hex | awk '{print $NF}'`
    s=`printf "aws4_request" | openssl sha256 -mac HMAC -macopt hexkey:"$s" -hex | awk '{print $NF}'`
    s=`printf "$p"           | openssl sha256 -mac HMAC -macopt hexkey:"$s" -hex | awk '{print $NF}'`

    key_and_sig_args="-F X-Amz-Credential=$aws_ak/$expdate_s/$region/$service/aws4_request -F X-Amz-Algorithm=AWS4-HMAC-SHA256 -F X-Amz-Signature=$s -F X-Amz-Date=${date}"
fi

# Upload. Supports anonymous upload if bucket is public-writable, and keys are set to ''.
echo "Uploading: $srcfile ($mime) to $bucket:$targfile"
curl -vvv                       \
    -# -kf                      \
    -F key=$targfile            \
    -F acl=$acl                 \
    $key_and_sig_args           \
    -F "Policy=$p"              \
    -F "Content-MD5=$md5"       \
    -F "Content-Type=$mime"     \
    -F "file=@$srcfile"         \
    https://${bucket}.s3.amazonaws.com/