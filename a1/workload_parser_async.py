import sys
import json
import os
import sqlite3
import asyncio
import aiohttp
from time import time

CONFIG_FILE = "config.json"
FIRST_COMMAND = True
REQUESTS_PER_SECOND = 10  # Configurable parameter

class RateLimiter:
    def __init__(self, rate_limit):
        self.rate_limit = rate_limit
        self.tokens = rate_limit
        self.last_update = time()
        self.lock = asyncio.Lock()

    async def acquire(self):
        async with self.lock:
            now = time()
            time_passed = now - self.last_update
            self.tokens = min(self.rate_limit, self.tokens + time_passed * self.rate_limit)
            self.last_update = now

            if self.tokens < 1:
                sleep_time = (1 - self.tokens) / self.rate_limit
                await asyncio.sleep(sleep_time)
                self.tokens = 0
                self.last_update = time()
            else:
                self.tokens -= 1

    async def __aenter__(self):
        await self.acquire()

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        pass


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


def read_Order_URL():
    if not os.path.exists(CONFIG_FILE):
        raise FileNotFoundError("Config File Not Found.")

    with open(CONFIG_FILE, "r") as f:
        config = json.load(f)

    order_ip = config["OrderService"]["ip"]
    order_port = config["OrderService"]["port"]

    return f"http://{order_ip}:{order_port}"


Order_URL = read_Order_URL()


async def send_request(session, method, endpoint, data=None, throttle=None):
    url = f"{Order_URL}{endpoint}"

    async with throttle:
        try:
            if method == "GET":
                async with session.get(url) as response:
                    resp_text = await response.text()
            else:
                headers = {"Content-Type": "application/json"}
                async with session.post(url, json=data, headers=headers) as response:
                    resp_text = await response.text()

            print(f"Request: {method} {url} - Data: {data}")
            print(f"Response: {response.status}: {resp_text}")
        except Exception as e:
            print(f"ERROR on {url}: {e}")


async def process_workload(file_path):
    global FIRST_COMMAND
    throttle = RateLimiter(rate_limit=REQUESTS_PER_SECOND)
    async with aiohttp.ClientSession() as session:
        with open(file_path, "r") as file:
            for line in file:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue

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

                entity, action = parts[0], parts[1]
                args = parts[2:]

                if entity == "USER":
                    await handle_user_command(session, action, args, throttle)
                elif entity == "PRODUCT":
                    await handle_product_command(session, action, args, throttle)
                elif entity == "ORDER":
                    await handle_order_command(session, args, throttle)
                else:
                    print("Invalid Command.")


async def handle_user_command(session, action, args, throttle):
    endpoint = "/user"

    if action == "create":
        user_id, username, email, password = (args + [""] * 4)[:4]
        data = {"command": "create", "id": safe_int(user_id), "username": username, "email": email, "password": password}
        await send_request(session, "POST", endpoint, data, throttle)

    elif action == "get":
        user_id = args[0]
        await send_request(session, "GET", f"{endpoint}/{user_id}", None, throttle)


async def handle_product_command(session, action, args, throttle):
    endpoint = "/product"

    if action == "create":
        product_id, name, description, price, quantity = (args + [""] * 5)[:5]
        data = {"command": "create", "id": safe_int(product_id), "name": name, "description": description, "price": safe_float(price), "quantity": safe_int(quantity)}
        await send_request(session, "POST", endpoint, data, throttle)

    elif action == "info":
        product_id = args[0]
        await send_request(session, "GET", f"{endpoint}/{product_id}", None, throttle)


async def handle_order_command(session, args, throttle):
    endpoint = "/order"
    product_id, user_id, quantity = (args + [""] * 3)[:3]
    data = {"command": "place order", "product_id": safe_int(product_id), "user_id": safe_int(user_id), "quantity": safe_int(quantity)}
    await send_request(session, "POST", endpoint, data, throttle)


def reset_databases():
    for db_file in ["./compiled/product.db", "./compiled/user.sqlite", "./compiled/order.db"]:
        if os.path.exists(db_file):
            os.remove(db_file)


def shutdown_all_services():
    print("Shutting down all services...")
    for service in ["user_service", "product_service", "order_service", "iscs"]:
        pid_file = f".{service}.pid"
        if os.path.exists(pid_file):
            with open(pid_file, "r") as f:
                pid = int(f.read().strip())
            os.kill(pid, 9)
            os.remove(pid_file)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: python script.py <workload_file>")

    workload_file = sys.argv[1]
    asyncio.run(process_workload(workload_file))