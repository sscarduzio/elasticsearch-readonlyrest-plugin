# Running integration tests with Newman
Newman is a tool to run Postman collections on the command line. 
By the way, you can import these collection and environment files in Postman and run the call interactively.

## Install Newman
```
npm install -g newman
```

## Run the integration tests
From the main project folder, build the project.
```
./build.sh
```

Build the docker image and run it with test configuration using the script
```
./test.sh
```

Optionally customise the `environment.json` file to match your environment. The default host name is `local.docker` (good enough if you run docker using [dlite](https://github.com/nlf/dlite)
```
vi environment.json
```

Finally run the collection. The `-y` option is used to introduce 5ms delay between calls to allow the indices to get correctly created before being queried.
```
newman -c collection.json -e environment.json -y 5
```
