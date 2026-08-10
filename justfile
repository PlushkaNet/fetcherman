tests:
    ./gradlew :app:testDebugUnitTest

test-fetch:
    ./gradlew :app:testDebugUnitTest --tests "plushkanet.fetcherman.HttpClientTest"

test-ping:
    ./gradlew :app:testDebugUnitTest --tests "*PingTest"

install-debug:
    ./gradlew installDebug

build:
    ./gradlew build
