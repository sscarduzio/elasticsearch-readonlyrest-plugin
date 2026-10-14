#!/bin/bash -e

# THE SAME FILE LIVES IN THREE REPOSITORIES. PORT EVERY CHANGE TO ALL THREE.
#
#   elasticsearch-readonlyrest-plugin   ci/s3-uploader.sh
#   readonlyrest_kbn                    scripts/utils/s3Uploader.sh
#   readonlyrest-e2e-tests              .github/scripts/s3-uploader.sh
#
# Below this header the three copies are identical, byte for byte, and `diff` proves it. Nothing
# here names a repository, and the file sources no other file: one of the three has no logger.
#
# The tests live in readonlyrest_kbn, scripts/tests/s3Uploader.test.sh. Run them there.

# Writes one message to standard error. Standard output stays empty, so a caller can capture a
# value from it.
s3_log() {
    echo "[s3-uploader] $*" >&2
}

usage()
{
    cat >&2 <<USAGE
##########################################################################
Script taken from https://www.aws.ps/how-to-upload-file-to-s3-using-curl

Simple script uploading a file to S3. Supports AWS signature version 4, custom
region, permissions and mime-types. Uses Content-MD5 header to guarantee
uncorrupted file transfer.

Usage:
  `basename $0` aws_ak aws_sk bucket srcfile targfile [mime_type]

Where <arg> is one of:
  aws_ak     access key ('' for upload to public writable bucket)
  aws_sk     secret key ('' for upload to public writable bucket)
  bucket     bucket name (with optional @region suffix, default is us-east-1)
  srcfile    path to source file
  targfile   path to target (dir if it ends with '/', relative to bucket root)
  mime_type  optional mime-type (tries to guess if omitted)

Exits 0 only when the server confirms the upload with 200, 201 or 204. Every
other answer is printed with the error body of the server. A transient one
(429, 5xx, or no answer at all) is retried until the retry budget is spent.

Optional environment variables:
  S3_ENDPOINT_URL  custom S3-compatible endpoint (uses path-style addressing).
                   Defaults to https://<bucket>.s3.amazonaws.com/ when unset.
  S3_UPLOAD_RETRY_BUDGET_SECONDS
                   how long this upload may spend waiting for a transient
                   failure to clear, in total (default 300). Sending the file
                   does not count. 0 switches the retry off.
  S3_UPLOAD_RETRY_DELAY_SECONDS
                   the wait before the second attempt, doubled for each one
                   after it and capped at 60 (default 5). A `Retry-After`
                   header of the server wins over it, up to 60s.
  S3_UPLOADER_MAX_TIME
                   maximum seconds for one attempt (curl --max-time). No limit
                   when unset.
  S3_UPLOADER_INSECURE
                   1 stops curl from verifying the certificate of the endpoint.
                   For a caller whose container holds no CA bundle.
  S3_UPLOADER_DEBUG
                   1 adds the curl trace, including the TLS handshake.

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
    # Non-zero: the argument check is the only caller, and a 0 here counts a file as uploaded
    # that nothing sent.
    exit 2
}

guessmime()
{
    mime=`file -b --mime-type "$1"`
    if [ "$mime" = "text/plain" ] || [ "$mime" = "application/octet-stream" ]; then
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
            *)               if head "$1" | grep '<html.*>' >/dev/null; then mime=text/html; fi;;
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
targfile=`echo -n "$5" | sed "s/\/$/\/$(basename "$srcfile")/"`          # target file
mime=${6:-"`guessmime "$srcfile"`"}                                      # mime type


# Signs one upload. Sets `p` (the base64 policy) and `key_and_sig_args` (credential, algorithm,
# signature, date, policy). Empty keys sign nothing, which is how an anonymous upload to a
# public-writable bucket works. Every attempt signs again, because the credential scope carries
# today's date and a retry can cross midnight.
sign_request()
{
    key_and_sig_args=''
    p=''
    if [ "$aws_ak" = "" ] || [ "$aws_sk" = "" ]; then
        return 0
    fi

    # SigV4 requires the credential scope date to match the request date.
    # `today_s` is used for both signing and the X-Amz-Credential field.
    # `expdate` is the policy expiration (tomorrow), unrelated to the credential date.
    local date today_s expdate service s
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

    # Policy goes here, not on the curl line: an anonymous upload must send no Policy field.
    key_and_sig_args="-F X-Amz-Credential=$aws_ak/$today_s/$region/$service/aws4_request -F X-Amz-Algorithm=AWS4-HMAC-SHA256 -F X-Amz-Signature=$s -F X-Amz-Date=${date} -F Policy=$p"
}

# True when a retry can give a different answer. $1 is the HTTP status, $2 the exit code of curl.
# A 429 or a 5xx is transient, and a 403 is not. Wrong credentials, a rejected policy condition and
# an empty file give the same answer every time, so the release must stop on them.
is_retryable()
{
    case "$1" in
        429|500|502|503|504) return 0;;
    esac
    # curl got no complete answer: a DNS failure, a refused connection, a timeout, a broken TLS
    # handshake. `--write-out` prints 000 for these, so the status cannot classify them.
    case "$2" in
        6|7|16|18|28|35|52|55|56|92) return 0;;
    esac
    return 1
}

# Determine upload URL.
# - If S3_ENDPOINT_URL is set (e.g. for the DGP proxy) use path-style addressing: ${endpoint}/${bucket}/
# - Otherwise default to AWS virtual-hosted-style: https://${bucket}.s3.amazonaws.com/
if [ -n "${S3_ENDPOINT_URL:-}" ]; then
    upload_url="${S3_ENDPOINT_URL%/}/${bucket}/"
else
    upload_url="https://${bucket}.s3.amazonaws.com/"
fi

# Off by default: a release uploads about 600 files, and the trace of each one makes the job log
# unreadable. The error body printed below attributes a failure without it.
if [ -n "${S3_UPLOADER_DEBUG:-}" ]; then
    CURL_FLAGS="-v -S"
else
    CURL_FLAGS=""
fi

# -k, only when the caller asks for it. A container with no CA bundle cannot verify the endpoint,
# and its uploads fail without this. Every other caller verifies.
if [ -n "${S3_UPLOADER_INSECURE:-}" ]; then
    CURL_INSECURE="-k"
else
    CURL_INSECURE=""
fi

# How long this file may spend waiting between attempts, in total. A budget, not a count of
# attempts: an attempt on a 40-byte .sha512 takes under a second and an attempt on a 50MB zip takes
# a minute or two, so the same count of attempts covers a 35-second outage for one and a
# six-minute outage for the other. A budget covers the same outage for both.
#
# Transfer time is deliberately outside it. The budget answers "how long do we wait for the storage
# to come back", and sending a file is not waiting.
budget=${S3_UPLOAD_RETRY_BUDGET_SECONDS:-300}
delay=${S3_UPLOAD_RETRY_DELAY_SECONDS:-5}
spent=0
attempt=1

# A value that is not a number makes every comparison below fail, which the loop reads as "budget
# left" and never ends.
case "$budget" in
    ''|*[!0-9]*) s3_log "S3_UPLOAD_RETRY_BUDGET_SECONDS must be a non-negative integer. Its value is '$budget'."; exit 2;;
esac
case "$delay" in
    ''|*[!0-9]*) s3_log "S3_UPLOAD_RETRY_DELAY_SECONDS must be a non-negative integer. Its value is '$delay'."; exit 2;;
esac

while true; do
    sign_request

    s3_log "Uploading: $srcfile ($mime) to $upload_url$targfile (attempt $attempt)"

    response_body=$(mktemp)
    response_headers=$(mktemp)
    curl_status=0
    # Quote the key: Cypress names a screenshot after its test, so it holds spaces and brackets.
    # Unquoted, the shell splits it and curl sends a truncated key, which the server rejects.
    # The file name goes in curl's own quotes too: in -F, an unquoted @path ends at a ',' or ';'.
    # $key_and_sig_args stays unquoted on purpose — it is several arguments, and must split.
    # --connect-timeout bounds a connection that never opens. S3_UPLOADER_MAX_TIME bounds one
    # attempt, and no caller that uploads a release zip sets it: such an upload runs for minutes.
    http_code=$(curl                \
        -# $CURL_INSECURE $CURL_FLAGS \
        --connect-timeout 30        \
        ${S3_UPLOADER_MAX_TIME:+--max-time "$S3_UPLOADER_MAX_TIME"} \
        -F key="$targfile"          \
        $key_and_sig_args           \
        -F "Content-Type=$mime"     \
        -F "file=@\"$srcfile\""     \
        --dump-header "$response_headers" \
        --write-out '%{http_code}'  \
        --output "$response_body"   \
        "$upload_url") || curl_status=$?

    # S3 answers 204 to a form upload, and 200 or 201 when the policy asks for it. Read the status,
    # not the exit code of curl. A 301 (bucket in another region) and a 307 (young bucket) both
    # leave the file unwritten and exit 0.
    case "$http_code" in
        200|201|204)
            rm -f "$response_body" "$response_headers"
            exit 0
            ;;
    esac

    s3_log "The upload of $srcfile to ${upload_url}${targfile} failed (HTTP $http_code, curl exit $curl_status)."
    # The error body names the cause. It carries no closing newline, so print one.
    [ -s "$response_body" ] && printf '%s\n' "$(<"$response_body")" >&2

    if ! is_retryable "$http_code" "$curl_status"; then
        s3_log "Another attempt gives the same result."
        rm -f "$response_body" "$response_headers"
        exit 1
    fi

    # `Retry-After` carries the wait the server asks for, and the cap bounds a wrong value.
    # `|| true` because a missing header is normal, and errexit would end the run on it.
    wait_s=$delay
    retry_after=$(tr -d '\r' < "$response_headers" | grep -i '^retry-after:' | tail -1 | sed 's/[^0-9]//g') || true
    if [ -n "$retry_after" ] && [ "$retry_after" -gt 0 ] 2>/dev/null; then
        wait_s=$retry_after
        [ "$wait_s" -gt 60 ] && wait_s=60
    fi
    rm -f "$response_body" "$response_headers"

    if [ "$(( spent + wait_s ))" -gt "$budget" ]; then
        s3_log "The upload of $srcfile failed, and its ${budget}s of retries are spent."
        exit 1
    fi

    s3_log "Another attempt starts in ${wait_s}s (${spent}s of ${budget}s spent)."
    sleep "$wait_s"
    spent=$(( spent + wait_s ))
    attempt=$((attempt + 1))
    # The cap keeps the last waits of a long budget from growing past a minute.
    delay=$((delay * 2))
    [ "$delay" -gt 60 ] && delay=60
done
