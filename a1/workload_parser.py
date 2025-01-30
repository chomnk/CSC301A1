import sys
import requests
import json
import os

CONFIG_FILE = "config.json"

def read_ISCS_URL():
    if not os.path.exists(CONFIG_FILE):
        raise FileNotFoundError("Config File Not Found.")

    with open(CONFIG_FILE, "r") as f:
        config = json.load(f)

    iscs_ip = config["InterServiceCommunication"]["ip"]
    iscs_port = config["InterServiceCommunication"]["port"]

    return f"http://{iscs_ip}:{iscs_port}"

ISCS_URL = read_ISCS_URL()

def send_request(method, endpoint, data=None):
    url = f"{ISCS_URL}{endpoint}"

    try:
        if method == "GET":
            response = requests.get(url)
        else:
            headers = {"Content-Type": "application/json"}
            response = requests.post(url, json=data, headers=headers)

        print(f"Request: {method} {url} - Data: {data}")
        print(f"Response: {response.status_code} - {response.text}")

    except requests.exceptions.RequestException as e:
        print(f"ERROR on {url}.")

def read_workload(file_path):
    with open(file_path, "r") as file:
        for line in file:
            line = line.strip()
            if not line or line.startswith("#"):
                continue

            parts = line.split()
            entity = parts[0]
            action = parts[1]

            if entity == "USER":
                handle_user_command(action, parts[2:])
            elif entity == "PRODUCT":
                handle_product_command(action, parts[2:])
            elif entity == "ORDER":
                handle_order_command(parts[1:])
            else:
                print("Invalid Command.")

def handle_user_command(action, args):
    endpoint = "/user"

    if action == "create":
        user_id, username, email, password = args
        data = {"command": "create", "id": int(user_id), "username": username, "email": email, "password": password}
        send_request("POST", endpoint, data)

    elif action == "update":
        user_id = int(args[0])
        updates = parse_update_fields(args[1:])
        data = {"command": "update", "id": user_id, **updates}
        send_request("POST", endpoint, data)

    elif action == "delete":
        user_id, username, email, password = args
        data = {"command": "delete", "id": int(user_id), "username": username, "email": email, "password": password}
        send_request("POST", endpoint, data)

    elif action == "get":
        user_id = args[0]
        send_request("GET", f"{endpoint}/{user_id}")

def handle_product_command(action, args):
    endpoint = "/product"

    if action == "create":
        product_id, name, description, price, quantity = args
        data = {"command": "create", "id": int(product_id), "productname": name, "price": float(price), "quantity": int(quantity)}
        send_request("POST", endpoint, data)

    elif action == "update":
        product_id = int(args[0])
        updates = parse_update_fields(args[1:])
        data = {"command": "update", "id": product_id, **updates}
        send_request("POST", endpoint, data)

    elif action == "delete":
        product_id, name, price, quantity = args
        data = {"command": "delete", "id": int(product_id), "productname": name, "price": float(price), "quantity": int(quantity)}
        send_request("POST", endpoint, data)

    elif action == "get":
        user_id = args[0]
        send_request("GET", f"{endpoint}/{user_id}")

def handle_order_command(args):
    product_id, user_id, quantity = args
    endpoint = "/order"
    data = {"command": "place order", "product_id": int(product_id), "user_id": int(user_id), "quantity": int(quantity)}
    send_request("POST", endpoint, data)

def parse_update_fields(fields):
    updates = {}
    for field in fields:
        key, value = field.split(":")
        if key in ["id", "quantity"]:
            updates[key] = int(value)
        elif key in ["price"]:
            updates[key] = float(value)
        else:
            updates[key] = value
    return updates

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(1)

    workload_file = sys.argv[1]
    parse_workload(workload_file)