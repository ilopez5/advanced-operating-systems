# Programming Assignment 2: Gnutella P2P Network

### Ismael J Lopez - ilopez5@hawk.iit.edu

---

## Peer Files
Peer files are provided for 16 peers with 10 files each. There are duplicates
across peers. Files are generated using
[Gensort](http://www.ordinal.com/gensort.html)
and are **not actual mp4 files**. I know the instructions said to generate
text files, these have text in them and are not binary files. These files are
located in the `files/` directory and are numbered by port number 6000-6016.

---

## Install
The code was developed and tested on an Ubuntu 20.04 machine using OpenJDK 13.
Ubuntu can be easily run on a Virtual Machine, or use WSL if on Windows.
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