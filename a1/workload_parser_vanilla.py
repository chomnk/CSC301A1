import sys
import asyncio
import shlex
import time
import os
import json
import requests

# --- Utility functions ---
def safe_int(value, default=-4321):
    try:
        return int(value)
    except (ValueError, TypeError):
        return default

def safe_float(value, default=-43.21):
    try:
        return float(value)
    except (ValueError, TypeError):
        return default

def read_order_url():
    config_file = "config.json"
    if not os.path.exists(config_file):
        raise FileNotFoundError("Config File Not Found.")
    with open(config_file, "r") as f:
        config = json.load(f)
    order_ip = config["OrderService"][0]["ip"]
    order_port = config["OrderService"][0]["port"]
    return f"http://{order_ip}:{order_port}"

# Set the order service URL (from config or you can hardcode it)
#ORDER_URL = read_order_url()
ORDER_URL = 'http://142.1.114.77:3000'

# --- Synchronous request function ---
def send_request(method, endpoint, data=None):
    url = f"{ORDER_URL}{endpoint}"
    try:
        if method == "GET":
            response = requests.get(url)
        else:
            headers = {"Content-Type": "application/json"}
            response = requests.post(url, json=data, headers=headers)
        # Print status code for debugging
        print(f"{method} {url} -> {response.status_code}")
        #print(f"Response: {response.status_code} - {response.text}")
    except requests.exceptions.RequestException as e:
        print(f"ERROR on {url}: {e}")

# --- Async wrapper ---
async def async_send_request(method, endpoint, data=None):
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, send_request, method, endpoint, data)

# --- Command handler functions ---
async def async_handle_user_command(action, args):
    endpoint = "/user"
    if action == "create":
        user_id, username, email, password = (args + [""] * 4)[:4]
        data = {
            "command": "create",
            "id": safe_int(user_id),
            "username": username,
            "email": email,
            "password": password
        }
        await async_send_request("POST", endpoint, data)
    elif action == "update":
        user_id = safe_int(args[0])
        updates = parse_update_fields(args[1:])
        data = {"command": "update", "id": user_id, **updates}
        await async_send_request("POST", endpoint, data)
    elif action == "delete":
        user_id, username, email, password = (args + [""] * 4)[:4]
        data = {
            "command": "delete",
            "id": safe_int(user_id),
            "username": username,
            "email": email,
            "password": password
        }
        await async_send_request("POST", endpoint, data)
    elif action == "get":
        user_id = args[0]
        await async_send_request("GET", f"{endpoint}/{user_id}")

async def async_handle_product_command(action, args):
    endpoint = "/product"
    if action == "create":
        product_id, name, description, price, quantity = (args + [""] * 5)[:5]
        data = {
            "command": "create",
            "id": safe_int(product_id),
            "name": name,
            "description": description,
            "price": safe_float(price),
            "quantity": safe_int(quantity)
        }
        await async_send_request("POST", endpoint, data)
    elif action == "update":
        product_id, name, description, price, quantity = (args + [""] * 5)[:5]
        updates = parse_update_fields(args[1:])
        data = {"command": "update", "id": safe_int(product_id), **updates}
        await async_send_request("POST", endpoint, data)
    elif action == "delete":
        product_id, name, price, quantity = (args + [""] * 4)[:4]
        data = {
            "command": "delete",
            "id": safe_int(product_id),
            "name": name,
            "price": safe_float(price),
            "quantity": safe_int(quantity)
        }
        await async_send_request("POST", endpoint, data)
    elif action == "info":
        prod_id = args[0]
        await async_send_request("GET", f"{endpoint}/{prod_id}")

async def async_handle_order_command(args):
    product_id, user_id, quantity = (args + [""] * 3)[:3]
    endpoint = "/order"
    data = {
        "command": "place order",
        "product_id": safe_int(product_id),
        "user_id": safe_int(user_id),
        "quantity": safe_int(quantity)
    }
    await async_send_request("POST", endpoint, data)

def parse_update_fields(fields):
    updates = {}
    for field in fields:
        key, value = field.split(":", 1)
        if key in ["id", "quantity"]:
            updates[key] = safe_int(value)
        elif key in ["price"]:
            updates[key] = safe_float(value)
        else:
            updates[key] = value
    return updates

# --- Data processing: Process each workload file line ---
async def process_workload_line(line):
    try:
        parts = shlex.split(line)
        if len(parts) < 2:
            return
        entity = parts[0].upper()
        action = parts[1].lower()
        args = parts[2:]
        if entity == "USER":
            await async_handle_user_command(action, args)
        elif entity == "PRODUCT":
            await async_handle_product_command(action, args)
        elif entity == "ORDER":
            await async_handle_order_command(args)
        else:
            print("Invalid command:", line)
    except Exception as e:
        print("Error processing line:", line, e)

# --- Throttling loop: Send commands at a fixed rate ---
async def send_requests_from_workload(file_path, requests_per_second):
    with open(file_path, "r") as f:
        lines = [line.strip() for line in f if line.strip() and not line.startswith("#")]
    total_lines = len(lines)
    if total_lines == 0:
        print("No workload commands found.")
        return

    interval = 1.0 / requests_per_second
    start_time = time.time()
    for i, line in enumerate(lines):
        # Schedule the processing of this line as a separate async task.
        asyncio.create_task(process_workload_line(line))
        await asyncio.sleep(interval)
    end_time = time.time()
    print(f"Processed {total_lines} commands in {end_time - start_time:.2f} seconds.")

# --- Main function ---
def main():
    if len(sys.argv) < 3:
        print("Usage: python workload.py <workload_file> <requests_per_second>")
        sys.exit(1)
    workload_file = sys.argv[1]
    rps = safe_int(sys.argv[2], 100)
    asyncio.run(send_requests_from_workload(workload_file, rps))

if __name__ == "__main__":
    main()
