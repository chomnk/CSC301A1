import json
import time

import requests
import sys
from deepdiff import DeepDiff

def send_post_request(url, data):
    """
    Function to send POST request to the given URL with the provided data.
    Even if the status code is not 200, we will still return the response for comparison.
    """
    try:
        response = requests.post(url, json=data)
        return response  # Return the full response object (status code + body)
    except requests.exceptions.RequestException as e:
        print(f"An error occurred: {e}")
        return None

def compare_responses(expected_data, actual_data):
    """
    Compare the expected response with the actual response (semantically).
    Uses deepdiff for more flexible comparison.
    """
    diff = DeepDiff(expected_data, actual_data, ignore_order=True, verbose_level=2)
    return diff

def process_json(file_path, url, expected_responses):
    """
    Function to process JSON file, send nested objects as POST requests, and compare the responses.
    """
    try:
        with open(file_path, 'r') as file:
            data = json.load(file)

        # Assuming the file contains one object with nested objects
        if isinstance(data, dict):
            for key, nested_object in data.items():
                print(f"Sending nested object under '{key}'...")
                response = send_post_request(url, nested_object)

                if response is not None:
                    actual_response = response.json() if response.status_code == 200 else response.text
                    expected_response = expected_responses.get(key, None)

                    if expected_response is not None:
                        diff = compare_responses(expected_response, actual_response)
                        if diff:
                            print(f"\nMismatch found for '{key}':")
                            print(diff)
                    else:
                        print(f"No expected response found for '{key}' in the second JSON file.")
                else:
                    print(f"Failed to send data for '{key}'.")

                time.sleep(0.5)

        else:
            print("The JSON is not structured as expected (should be a dictionary).")
    except FileNotFoundError:
        print(f"File '{file_path}' not found.")
    except json.JSONDecodeError:
        print(f"Error decoding JSON from file '{file_path}'.")

def main():
    """
    Main function that drives the script.
    """
    if len(sys.argv) != 4:
        print("Usage: python send_and_compare_json.py <json_file1> <json_file2> <url>")
        sys.exit(1)

    json_file1 = sys.argv[1]  # The file to send data from
    json_file2 = sys.argv[2]  # The file containing expected responses
    url = sys.argv[3]         # The URL to send requests to

    # Load expected responses from the second JSON file
    try:
        with open(json_file2, 'r') as file:
            expected_responses = json.load(file)
    except FileNotFoundError:
        print(f"File '{json_file2}' not found.")
        sys.exit(1)
    except json.JSONDecodeError:
        print(f"Error decoding JSON from file '{json_file2}'.")
        sys.exit(1)

    process_json(json_file1, url, expected_responses)

if __name__ == "__main__":
    main()
