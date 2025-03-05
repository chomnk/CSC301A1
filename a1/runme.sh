#!/bin/bash

BASE_DIR=$(dirname "$0")
SRC_DIR="$BASE_DIR/src"
COMPILED_DIR="$BASE_DIR/compiled"

USER_SERVICE_DIR="$SRC_DIR/UserService"
PRODUCT_SERVICE_DIR="$SRC_DIR/ProductService"
ORDER_SERVICE_DIR="$SRC_DIR/OrderService"
ISCS_DIR="$SRC_DIR/ISCS"

USER_COMPILED="$COMPILED_DIR/UserService"
PRODUCT_COMPILED="$COMPILED_DIR/ProductService"
ORDER_COMPILED="$COMPILED_DIR/OrderService"
ISCS_COMPILED="$COMPILED_DIR/ISCS"

mkdir -p "$USER_COMPILED" "$PRODUCT_COMPILED" "$ORDER_COMPILED" "$ISCS_COMPILED"

compile() {
    wget -nc -P "./compiled/" https://repo1.maven.org/maven2/com/google/code/gson/gson/2.12.0/gson-2.12.0.jar
    wget -nc -P "./compiled/" https://repo1.maven.org/maven2/org/json/json/20210307/json-20210307.jar
    wget -nc -P "./compiled/" https://repo1.maven.org/maven2/commons-codec/commons-codec/1.18.0/commons-codec-1.18.0.jar
    wget -nc -P "./compiled/" https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.48.0.0/sqlite-jdbc-3.48.0.0.jar
    javac -cp "./compiled/json-20210307.jar:./compiled/commons-codec-1.18.0.jar" -d "$USER_COMPILED" "$USER_SERVICE_DIR"/src/*.java || { echo "UserService failed"; exit 1; }
    javac -cp "./compiled/gson-2.12.0.jar:./compiled/json-20210307.jar" -d "$PRODUCT_COMPILED" "$PRODUCT_SERVICE_DIR"/src/*.java || { echo "ProductService failed"; exit 1; }
    javac -cp "./compiled/json-20210307.jar" -d "$ORDER_COMPILED" "$ORDER_SERVICE_DIR"/src/*.java || { echo "OrderService failed"; exit 1; }
    javac -cp "./compiled/json-20210307.jar" -d "$ISCS_COMPILED" "$ISCS_DIR"/src/*.java || { echo "ISCS failed"; exit 1; }
}

start() {
    case $1 in
        -u)
            java -cp "$USER_COMPILED:./compiled/json-20210307.jar:./compiled/sqlite-jdbc-3.48.0.0.jar:./compiled/commons-codec-1.18.0.jar" Main "$CONFIG_FILE" & echo $! > "$BASE_DIR/.user_service.pid"
            ;;
        -p)
            java -cp "$PRODUCT_COMPILED:./compiled/gson-2.12.0.jar:./compiled/json-20210307.jar:./compiled/sqlite-jdbc-3.48.0.0.jar" Main "$CONFIG_FILE" & echo $! > "$BASE_DIR/.product_service.pid"
            ;;
        -o)
            java -cp "$ORDER_COMPILED:./compiled/json-20210307.jar:./compiled/sqlite-jdbc-3.48.0.0.jar" Main "$CONFIG_FILE" & echo $! > "$BASE_DIR/.order_service.pid"
            ;;
        -i)
            java -cp "$ISCS_COMPILED:./compiled/json-20210307.jar" Main "$CONFIG_FILE" & echo $! > "$BASE_DIR/.iscs.pid"
            ;;
        -w)
            python3 "$BASE_DIR/workload_parser.py" "$2" &
            ;;
        *)
            echo "ERROR Format: $1"
            exit 1
            ;;
    esac
}

if [ $# -lt 1 ]; then
  echo "Usage: $0 -c | [-u|-p|-o|-i] [config.json] | -w <workload.txt>"
  exit 1
fi

if [[ "$1" == "-c" ]]; then
    compile
else
    if [[ "$1" == "-w" ]]; then
        if [ $# -lt 2 ]; then
          echo "Usage: $0 -w <workload.txt>"
          exit 1
        fi
    else
        if [ $# -lt 2 ]; then
          CONFIG_FILE="$BASE_DIR/config.json"
        else
          CONFIG_FILE="$2"
        fi
    fi
    start "$@"
fi