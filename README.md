# jms-dude
## Commandline JMS queue browser and message forwarder
jms-dude is a Groovy based ActiveMQ client who lets you easily browse queues, 
forward messages to queues or topics and delete messages in transactional manner. 

## Installation
Open your favourite terminal and enter the following:
```shell script
$ curl -s https://raw.githubusercontent.com/begris/jms-dude/master/install-jms-dude.sh | bash
```
If the environment needs tweaking for jms-dude to be installed, the installer will prompt you accordingly and ask you to restart.

## Usage
```shell script
jms-dude --help
Usage:

Groovy script jms-dude

jms-dude ([--auto-completion] | [-b=<brokerUrl> -q=<queue> [--credential=FILE |
         [-u=<user> [-P[=<password>]] [-P[=<password>]]...]]]) [[-f=<forward>]
         -s=<selector>] [-a | -p=property name[,property name...] [-p=property 
         name[,property name...]]...] [-t | --dump | [-j [-c]]] [-dhV] [FILE...]

Description:

Browse and dumps messages on a jms queue with optional forwarding of messages

Parameters:
      [FILE...]              messages to send

Options:
  -h, --help                 Show this help message and exit.
  -V, --version              Print version information and exit.
      --auto-completion      Genereate bash-completion script for jms-dude
  -b, --broker=<brokerUrl>   AMQ broker url to query
  -u, --user=<user>          User for AMQ
      --credential=FILE      Credential file for AMQ - format: <username>:
                               <password>
  -P, --password[=<password>]
                             Password for AMQ
  -q, --queue=<queue>        The queue to browse
  -f, --forward=<forward>    The queue or topic to forward messages to. Format
                               queue://name | topic://name
  -s, --selector=<selector>  JMS message selector
  -d, --delete               delete processed messages from queue
  -a, --all                  retrieve all message porperties
  -p, --properties=property name[,property name...]
                             message properties to retrieve
                             property names are treated as regular expressions
  -t, --table                show messages as table
      --dump                 dump messages
  -j, --json                 output in json format
  -c, --body                 dumps the message body (json only)
```
## Features

## Examples


