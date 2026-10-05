# Health Check and Routing Algorithm

## 1. Background Health Checks (Continuous Process)

This process runs independently of incoming requests to maintain an up-to-date status of all models.

* **Initialization:** Sort all configured models by priority (highest to lowest).

* **Staggered Startup:** Iterate through the sorted list and spawn a new dedicated thread for each model. Wait **15 seconds** before starting the thread for the next model.

* **Thread Execution Loop (Repeats every 60 seconds):**

  1. Send a health check request for the model. Fetch the required API key for this model using the Round Robin logic.

  2. Await a response with a timeout of **120 seconds**.

  3. If the request succeeds: Update the model's status to **AVAILABLE**.

  4. If the request fails or times out: Update the model's status to **UNAVAILABLE**.

  5. Sleep for **60 seconds** before triggering the next check.

## 2. API Key Management (Round Robin)

For models configured with multiple provider keys, maintain an isolated tracking index to ensure even usage.

* Initialize a `current_key_index` at 0 for each model.

* Whenever a model is selected to handle a request, fetch the key at `current_key_index`.

* Immediately increment the index for the next call: `current_key_index = (current_key_index + 1) % total_number_of_keys`.

## 3. Request Routing Execution

When a user request comes in, it must specify a **Target Category**. Execute the following strict sequence to ensure the best available model in that category is used:

1. **Filter by Category and Availability:** Retrieve a list of all models. Filter this list to include ONLY models that match the **Target Category** AND are currently marked as **AVAILABLE** by the health check threads.

2. **Sort by Priority:** Sort the filtered list strictly by highest priority first.

3. **Select Model and Key:** Select the highest priority model from the top of the list. Fetch the required API key for this model using the Round Robin logic.

4. **Execute Request:** Send the user's request to the selected model using the fetched key.

5. **Handle Response or Fallback:**

   * If the request is successful, return the response to the user and terminate the sequence.

   * If the request fails, remove the current model from your available list and loop back to **Step 3** with the next highest priority model in that category.

6. **Exhaustion:** If you loop through the entire list of available models and all of them fail (or if the initial filtered list of available models was empty), return an **"All models in the requested category are unavailable"** error to the user.
