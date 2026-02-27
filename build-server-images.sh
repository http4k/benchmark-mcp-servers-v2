#!/bin/bash
set -e

echo "🛠️  Starting Sequential Docker Build (One by one) to prevent VM freezing..."

# Get the list of services defined in docker-compose.yml
services=$(docker compose config --services)

# Iterate over each service
for service in $services; do
    echo "=========================================================="
    echo "⏳ Building: $service ..."
    echo "=========================================================="
    
    # Run the build specifically for THIS service only
    if docker compose build "$service"; then
        echo "✅ Successfully built: $service"
    else
        echo "❌ ERROR! Failed to build $service. Stopping script."
        exit 1
    fi
    
    echo "🌬️ Pausing for 15 seconds to flush dirty memory and cool down CPU..."
    sleep 15
done

echo "🎉 ALL BUILDS COMPLETED SUCCESSFULLY!"
