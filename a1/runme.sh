#!/bin/bash

BASE_DIR=$(dirname "$0")
SRC_DIR="$BASE_DIR/src"
COMPILED_DIR="$BASE_DIR/compiled"

USER_SERVICE_DIR="$SRC_DIR/UserService"
PRODUCT_SERVICE_DIR="$SRC_DIR/ProductService"
ORDER_SERVICE_DIR="$SRC_DIR/OrderService"
ISCS_DIR="$SRC_DIR/ISCS"
LOAD_BALANCER_DIR="$SRC_DIR/LoadBalancer"

USER_COMPILED="$COMPILED_DIR/UserService"
PRODUCT_COMPILED="$COMPILED_DIR/ProductService"
ORDER_COMPILED="$COMPILED_DIR/OrderService"
ISCS_COMPILED="$COMPILED_DIR/ISCS"
LOAD_BALANCER_COMPILED="$COMPILED_DIR/LoadBalancer"

mkdir -p "$USER_COMPILED" "$PRODUCT_COMPILED" "$ORDER_COMPILED" "$ISCS_COMPILED" "$LOAD_BALANCER_COMPILED"

compile() {
    wget -nc -P "./compiled/" https://repo1.maven.org/maven2/com/google/code/gson/gson/2.12.0/gson-2.12.0.jar
    wget -nc -P "./compiled/" https://repo1.maven.org/maven2/org/json/json/20210307/json-20210307.jar
    wget -nc -P "./compiled/" https://repo1.maven.org/maven2/commons-codec/commons-codec/1.18.0/commons-codec-1.18.0.jar
    wget -nc -P "./compiled/" https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.48.0.0/sqlite-jdbc-3.48.0.0.jar
    javac -cp "./compiled/json-20210307.jar:./compiled/commons-codec-1.18.0.jar" -d "$USER_COMPILED" "$USER_SERVICE_DIR"/src/*.java || { echo "UserService failed"; exit 1; }
    javac -cp "./compiled/gson-2.12.0.jar:./compiled/json-20210307.jar" -d "$PRODUCT_COMPILED" "$PRODUCT_SERVICE_DIR"/src/*.java || { echo "ProductService failed"; exit 1; }
    javac -cp "./compiled/json-20210307.jar" -d "$ORDER_COMPILED" "$ORDER_SERVICE_DIR"/src/*.java || { echo "OrderService failed"; exit 1; }
    javac -cp "./compiled/json-20210307.jar" -d "$ISCS_COMPILED" "$ISCS_DIR"/src/*.java || { echo "ISCS failed"; exit 1; }

    javac -cp "./compiled/json-20210307.jar" -d "$LOAD_BALANCER_COMPILED" "$LOAD_BALANCER_DIR"/src/*.java || { echo "Load Balancer failed"; exit 1; }
}

start() {
    case $1 in
        -u)
            #java -cp "$USER_COMPILED:./compiled/json-20210307.jar:./compiled/sqlite-jdbc-3.48.0.0.jar:./compiled/commons-codec-1.18.0.jar" Main "$CONFIG_FILE" & echo $! > "$BASE_DIR/.user_service.pid"
            #;;
            NUM_USER_INSTANCES=30  # Adjust based on your config
            for i in $(seq 0 $((NUM_USER_INSTANCES - 1))); do
                java -cp "$USER_COMPILED:./compiled/json-20210307.jar:./compiled/sqlite-jdbc-3.48.0.0.jar:./compiled/commons-codec-1.18.0.jar" Main "$CONFIG_FILE" "$i" &
                echo $! >> "$BASE_DIR/.user_service.pid"
            done
            ;;
        -p)
            #java -cp "$PRODUCT_COMPILED:./compiled/gson-2.12.0.jar:./compiled/json-20210307.jar:./compiled/sqlite-jdbc-3.48.0.0.jar" Main "$CONFIG_FILE" & echo $! > "$BASE_DIR/.product_service.pid"
            #;;
            NUM_PRODUCT_INSTANCES=30  # Adjust based on your config
            for i in $(seq 0 $((NUM_PRODUCT_INSTANCES - 1))); do
                java -cp "$PRODUCT_COMPILED:./compiled/gson-2.12.0.jar:./compiled/json-20210307.jar:./compiled/sqlite-jdbc-3.48.0.0.jar" Main "$CONFIG_FILE" "$i" &
                echo $! >> "$BASE_DIR/.product_service.pid"
            done
            ;;
        -o)
            #java -cp "$ORDER_COMPILED:./compiled/json-20210307.jar:./compiled/sqlite-jdbc-3.48.0.0.jar" Main "$CONFIG_FILE" & echo $! > "$BASE_DIR/.order_service.pid"
            #;;

            # Start all OrderService instances (assumed 6 instances)
            NUM_INSTANCES=20
            for i in $(seq 0 $((NUM_INSTANCES - 1))); do
                java -cp "$ORDER_COMPILED:./compiled/json-20210307.jar:./compiled/sqlite-jdbc-3.48.0.0.jar" Main "$CONFIG_FILE" "$i" &
                echo $! >> "$BASE_DIR/.order_service.pid"
            done
            ;;
        -i)
            java -cp "$ISCS_COMPILED:./compiled/json-20210307.jar" Main "$CONFIG_FILE" & echo $! > "$BASE_DIR/.iscs.pid"
            ;;
        -l)
            if [ $# -ne 3 ]; then
                echo "Usage: $0 -l <ip> <port>"
                exit 1
            fi
            LB_IP="$2"
            LB_PORT="$3"
            java -cp "$LOAD_BALANCER_COMPILED:./compiled/json-20210307.jar" Main "$LB_IP" "$LB_PORT" & echo $! > "$BASE_DIR/.load_balancer.pid"
            ;;
        -w)
            python3 "$BASE_DIR/workload_parser_vanilla.py" "$2" "6000" &
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