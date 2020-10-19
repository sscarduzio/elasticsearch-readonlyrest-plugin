ESVERSIONS=$(for i in `ls es*x/gradle.properties`;do awk -F= {'print $2'} "$i"; done)
for VERSION in $ESVERSIONS
do
  echo "check actions for $VERSION if needed"
  ci/actionstrings/fetchIfNecessary.sh $VERSION
done
echo "done checking action strings."
