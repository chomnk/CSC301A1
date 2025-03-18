import sys
import requests
import json
import os
import sqlite3

CONFIG_FILE = "config.json"
FIRST_COMMAND = True

def safe_int(value, default = -4321):
    try:
        return int(value)
    except (ValueError, TypeError):
        return default

def safe_float(value, default = -43.21):
    try:
        return float(value)
    except (ValueError, TypeError):
        return default

def read_Order_URL():
    if not os.path.exists(CONFIG_FILE):
        raise FileNotFoundError("Config File Not Found.")

    with open(CONFIG_FILE, "r") as f:
        config = json.load(f)

    order_ip = config["OrderService"]["ip"]
    order_port = config["OrderService"]["port"]

    return f"http://{order_ip}:{order_port}"

Order_URL = read_Order_URL()

counter = 1

def send_request(method, endpoint, data=None):
    global counter

    url = f"{Order_URL}{endpoint}"

    try:
        if method == "GET":
            response = requests.get(url)
        else:
            headers = {"Content-Type": "application/json"}
            response = requests.post(url, json=data, headers=headers)

        print(f"Request: {method} {url} - Data: {data}")
        #print(f"Response: {response.status_code} - {response.text}")
        print(f"{counter}: {response.status_code}")
        counter += 1

    except requests.exceptions.RequestException as e:
        print(f"ERROR on {url}.")

def read_workload(file_path):
    with open(file_path, "r") as file:
        for line in file:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            global FIRST_COMMAND
            if FIRST_COMMAND:
                if line != "restart":
                    con = sqlite3.connect("./compiled/product.db")
                    cur = con.cursor()
                    cur.execute("DROP TABLE IF EXISTS products")
                    cur.execute("""CREATE TABLE IF NOT EXISTS products (
                                        id INTEGER PRIMARY KEY,
                                        productname VARCHAR(255),
                                        description VARCHAR(255),
                                        price FLOAT,
                                        quantity INT
                                        );""")
                    cur.close()
                    con.close()
                    con = sqlite3.connect("./compiled/user.sqlite")
                    cur = con.cursor()
                    cur.execute("DROP TABLE IF EXISTS user")
                    cur.execute("""CREATE TABLE IF NOT EXISTS user (
                                        id INTEGER PRIMARY KEY,
                                        username VARCHAR(255),
                                        email VARCHAR(225),
                                        password CHAR(64)
                                        )""")
                    cur.close()
                    con.close()
                    con = sqlite3.connect("./compiled/order.db")
                    cur = con.cursor()
                    cur.execute("DROP TABLE IF EXISTS purchases")
                    cur.execute("""
                        CREATE TABLE IF NOT EXISTS purchases (
                            user_id INTEGER,
                            product_id INTEGER,
                            quantity INTEGER,
                            PRIMARY KEY(user_id, product_id)
                        );
                    """)
                    cur.close()
                    con.close()
                FIRST_COMMAND = False

            if line == "shutdown":
                shutdown_all_services()
                break

            parts = line.split()

            if len(parts) < 2:
                continue

            entity = parts[0]
            action = parts[1]

            if entity == "USER":
                handle_user_command(action, parts[2:])
            elif entity == "PRODUCT":
                handle_product_command(action, parts[2:])
            elif entity == "ORDER":
                handle_order_command(parts[2:])
            else:
                print("Invalid Command.")

def handle_user_command(action, args):
    endpoint = "/user"

    if action == "create":
        user_id, username, email, password = (args + [""] * 4)[:4]
        data = {"command": "create", "id": safe_int(user_id), "username": username, "email": email, "password": password}
        send_request("POST", endpoint, data)

    elif action == "update":
        user_id = safe_int(args[0])
        if user_id == -4321:
            user_id = ""
        updates = parse_update_fields(args[1:])
        data = {"command": "update", "id": user_id, **updates}
        send_request("POST", endpoint, data)

    elif action == "delete":
        user_id, username, email, password = (args + [""] * 4)[:4]
        data = {"command": "delete", "id": safe_int(user_id), "username": username, "email": email, "password": password}
        send_request("POST", endpoint, data)

    elif action == "get":
        user_id = args[0]
        send_request("GET", f"{endpoint}/{user_id}")

def handle_product_command(action, args):
    endpoint = "/product"

    if action == "create":
        product_id, name, description, price, quantity = (args + [""] * 5)[:5]

        if safe_int(quantity) != safe_float(quantity):
            quantity = ""

        data = {"command": "create", "id": safe_int(product_id), "name": name, "description": description, "price": safe_float(price), "quantity": safe_int(quantity)}
        send_request("POST", endpoint, data)

    elif action == "update":
        product_id, name, description, price, quantity = (args + [""] * 5)[:5]

        if safe_int(quantity) != safe_float(quantity):
            quantity = ""

        updates = parse_update_fields(args[1:])
        data = {"command": "update", "id": safe_int(product_id), **updates}
        send_request("POST", endpoint, data)

    elif action == "delete":
        product_id, name, description, price, quantity = (args + [""] * 5)[:5]

        if safe_int(quantity) != safe_float(quantity):
            quantity = ""

        data = {"command": "delete", "id": safe_int(product_id), "name": name, "description": description, "price": safe_float(price), "quantity": safe_int(quantity)}
        send_request("POST", endpoint, data)

    elif action == "info":
        user_id = args[0]
        send_request("GET", f"{endpoint}/{user_id}")

def handle_order_command(args):
    product_id, user_id, quantity = (args + [""] * 3)[:3]
    endpoint = "/order"
    data = {"command": "place order", "product_id": safe_int(product_id), "user_id": safe_int(user_id), "quantity": safe_int(quantity)}
    send_request("POST", endpoint, data)

def parse_update_fields(fields):
    updates = {}
    for field in fields:
        key, value = field.split(":")
        if key in ["id", "quantity"]:
            updates[key] = safe_int(value)
        elif key in ["price"]:
            updates[key] = safe_float(value)
        else:
            updates[key] = value
    return updates

def shutdown_user_service():
    f = open(".user_service.pid", "r")
    pid = int(f.read())
    f.close()
    os.kill(pid, 9)
    os.remove(".user_service.pid")

def shutdown_product_service():
    f = open(".product_service.pid", "r")
    pid = int(f.read())
    f.close()
    os.kill(pid, 9)
    os.remove(".product_service.pid")

def shutdown_order_service():
    f = open(".order_service.pid", "r")
    pid = int(f.read())
    f.close()
    os.kill(pid, 9)
    os.remove(".order_service.pid")

def shutdown_iscs_service():
    f = open(".iscs.pid", "r")
    pid = int(f.read())
    f.close()
    os.kill(pid, 9)
    os.remove(".iscs.pid")

def shutdown_all_services():
    shutdown_user_service()
    shutdown_product_service()
    shutdown_order_service()
    shutdown_iscs_service()

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(1)

    workload_file = sys.argv[1]
    read_workload(workload_file)