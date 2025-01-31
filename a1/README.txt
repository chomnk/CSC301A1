Please run ./runme.sh -c in the current directory to compile the code.

To start the services, please run:

./runme.sh -u [config.json] to start the user service
./runme.sh -p [config.json] to start the product service
./runme.sh -o [config.json] to start the order service
./runme.sh -i [config.json] to start the ISCS

Running the above commands without the [config.json] argument will start the services with the default config.json file
in the a1 folder.

To run the workloads:

./runme.sh -w <workload.txt>

IMPORTANT:
When copying to different machines, please make sure that product.db, user.sqlite and the jar files are all copied along with it.
Please copy the entire a1 folder to the new machine so that runme.sh and config.json are also included.
Please start the respective services ONLY with the runme.sh script.