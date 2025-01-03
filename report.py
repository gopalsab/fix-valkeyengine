import pandas as pd
import matplotlib.pyplot as plt

# Try to read the file, inferring the column names
df = pd.read_csv('results.jtl', header=None)

# Print the first few rows to see the structure
print(df.head())

# Assuming the first column is timestamp and the second is elapsed time
df.columns = ['timestamp', 'elapsed'] + list(df.columns[2:])

# Convert timestamp to datetime
df['timestamp'] = pd.to_datetime(df['timestamp'], unit='ms')

# Plot response times
plt.figure(figsize=(12,6))
plt.plot(df['timestamp'], df['elapsed'])
plt.title('Response Time Over Time')
plt.xlabel('Time')
plt.ylabel('Response Time (ms)')
plt.show()

# Calculate and plot throughput
df['throughput'] = 1 / (df['elapsed'] / 1000)  # requests per second
plt.figure(figsize=(12,6))
plt.plot(df['timestamp'], df['throughput'])
plt.title('Throughput Over Time')
plt.xlabel('Time')
plt.ylabel('Requests per Second')
plt.show()
