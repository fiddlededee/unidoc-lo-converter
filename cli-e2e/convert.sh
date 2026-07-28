echo "📁 Preparing build folders..."
rm cli-e2e/output -rf
mkdir -p cli-e2e/output
mkdir -p cli-e2e/output/local
mkdir -p cli-e2e/output/docker
cp cli-e2e/inputs/* cli-e2e/output/local
cp cli-e2e/inputs/* cli-e2e/output/docker

echo "🔨 Building project..."
./gradlew clean build
VERSION=$(./gradlew -q :properties | grep "^version:" | awk '{print $2}')
JAR="cli/build/libs/unidoc-lo-converter-cli-${VERSION}.jar"

echo "📍 Test 1: Local LibreOffice"
java -jar "$JAR" cli-e2e/output/local/non-existant.fodt cli-e2e/output/local/test-?.* -f PDF -f HTML --start-local
echo "✅ Local test finished"

echo "🐳 Building Docker image..."
pushd lo-docker || exit
docker build -t lotest .
popd || exit

echo "🚀 Starting LibreOffice in Docker..."
docker stop lo && docker rm lo || true
docker run -d --name lo \
    --network host \
    -v $(pwd)/cli-e2e:/documents \
    lotest \
    soffice --headless --accept="socket,host=0.0.0.0,port=2002;urp;" --norestore --nofirststartwizard

echo "📍 Test 2: LibreOffice in Docker"
java -jar "$JAR"  /documents/output/docker/test-1.odt /documents/output/docker/test-2.docx -f PDF --host 127.0.0.1 --port 2002
echo "✅ Docker test finished"

echo "🔍 Comparing PDF outputs (first different, second identical)..."
PDF_COMPARE_JAR="cli-pdf-compare/build/libs/unidoc-pdf-compare-cli-${VERSION}.jar"

java -jar "$PDF_COMPARE_JAR" \
  --compare cli-e2e/output/docker/test-1.pdf cli-e2e/output/docker/test-2.pdf cli-e2e/output/docker/test.diff.pdf \
  --compare cli-e2e/output/docker/test-1.pdf cli-e2e/output/docker/test-1.pdf cli-e2e/output/docker/test.diff.pdf
echo "✅ PDF comparison complete"

echo "🧹 Stopping container..."
docker stop lo && docker rm lo
echo "✅ Done"