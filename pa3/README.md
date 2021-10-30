# Programming Assignment 3: Gnutella P2P Network with Consistency

### Ismael J Lopez - ilopez5@hawk.iit.edu

---

## Peer Files
Peer files are provided for 16 peers with 10 files each. There are duplicates
across peers. Files are generated using
[Gensort](http://www.ordinal.com/gensort.html)
and are **not actual mp4 files**. I know the instructions said to generate
text files, these have text in them and are not binary files. These files are
located in the `files/` directory and are numbered by port number 6001-6016.

---

## Install
The code was developed and tested on an Ubuntu 20.04 machine using OpenJDK 13.
Ubuntu can be easily installed on a Virtual Machine, or use WSL if on Windows.
OpenJDK-13 can be installed on Ubuntu using the following command:
```bash
$ sudo apt install openjdk-13-jdk
```

---

## Build
A `Makefile` has been provided. Running `make` will compile all the code and
running `make clean` will remove any `.class` files.

Examples:
```bash
$ make # compiles
$ make clean # cleans the auxiliary files
```

Screenshot:
![make](#)

---

## Running

### Topology Config file
Please define a single topology config file which defines the network. The
syntax for this topology is as follows:

```bash
s <address1:port1> <address2:port2> # defines the SuperPeer neighbor
s 127.0.0.1:5000 127.0.0.1:5001 # e.g., SuperPeer 5001 is neighbor to SuperPeer 5000
s 127.0.0.1:5001 127.0.0.1:5000 # e.g., SuperPeer 5000 is neighbor to SuperPeer 5001

p <address1:port1> <address2:port2> # defines the leaf peer of a SuperPeer
p 127.0.0.1:5000 127.0.0.1:6001 # e.g., Leaf Peer 6001 is under SuperPeer 5000
p 127.0.0.1:5001 127.0.0.1:6003 # e.g., Leaf Peer 6003 is under SuperPeer 5001
```
There are topology files already included. The `*.simple.config`  file contains
a smaller network of 3 SuperPeers, each with 1 Leaf Peer. The `*.full.config` file
contains the network requested in the instructions: 8 SuperPeers with 1-3 Leaf
Peers each.

### Launching SuperPeers
To launch a SuperPeer, run the program while passing in the IPv4 address and
port, and the config file containing the static topology of the network.

Example(s):
```bash
$ java SuperPeer 127.0.0.1:5000 all-to-all.config
$ java SuperPeer 127.0.0.1:5000 linear.config
```

Screenshot:
![sp](#)

### Launching Peers
To launch a Peer, or leaf peer, run the program while passing in similar arguments
to the SuperPeer, with the addition of a file directory containing the Peer's
files. See below:

Example(s):
```bash
$ java Peer 127.0.0.1:6001 files/peer6001 all-to-all.config
$ java Peer 127.0.0.1:6001 files/peer6001 linear.config
```

Screenshot:
![p](#)