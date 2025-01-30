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

CONFIG_FILE="$BASE_DIR/config.json"

mkdir -p "$USER_COMPILED" "$PRODUCT_COMPILED" "$ORDER_COMPILED" "$ISCS_COMPILED"

compile() {
    javac -cp "./lib/org.json.jar" -d "$USER_COMPILED" "$USER_SERVICE_DIR"/src/*.java || { echo "UserService failed"; exit 1; }
    javac -cp "./lib/gson-2.12.0.jar:./lib/org.json.jar" -d "$PRODUCT_COMPILED" "$PRODUCT_SERVICE_DIR"/src/*.java || { echo "ProductService failed"; exit 1; }
    javac -cp "./lib/org.json.jar" -d "$ORDER_COMPILED" "$ORDER_SERVICE_DIR"/src/*.java || { echo "OrderService failed"; exit 1; }
    javac -cp "./lib/org.json.jar" -d "$ISCS_COMPILED" "$ISCS_DIR"/src/*.java || { echo "ISCS failed"; exit 1; }
}

start() {
    case $1 in
        -u)
            java -cp "$USER_COMPILED" Main "$CONFIG_FILE" &
            ;;
        -p)
            java -cp "$PRODUCT_COMPILED" Main "$CONFIG_FILE" &
            ;;
        -o)
            java -cp "$ORDER_COMPILED" Main "$CONFIG_FILE" &
            ;;
        -i)
            java -cp "$ISCS_COMPILED" Main "$CONFIG_FILE" &
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

if [[ "$1" == "-c" ]]; then
    compile
else
    start "$@"
fi