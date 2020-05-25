#!/usr/bin/env groovy
package com.begris.jms.dude

import org.apache.activemq.command.ActiveMQBlobMessage
import org.fusesource.hawtbuf.UTF8Buffer
@Grab('info.picocli:picocli-groovy:4.3.2')
@GrabConfig(systemClassLoader = true)
import picocli.CommandLine
import picocli.AutoComplete
import picocli.groovy.PicocliScript

@Grab('org.apache.karaf.shell:org.apache.karaf.shell.table:4.2.8')
import org.apache.karaf.shell.table.*
import groovy.json.JsonBuilder

@Grab('org.apache.activemq:activemq-all:5.15.12')
import org.apache.activemq.ActiveMQConnectionFactory
import javax.jms.*

@CommandLine.Command(name = "jms-dude",
        mixinStandardHelpOptions = true, // add --help and --version options
        version = '1.0.0-Snapshot',
        sortOptions = false,
        headerHeading = "Usage:%n%n",
        synopsisHeading = "%n",
        descriptionHeading = "%nDescription:%n%n",
        parameterListHeading = "%nParameters:%n",
        optionListHeading = "%nOptions:%n",
        header = "@|bold Groovy script|@ @|underline jms-dude|@",
        description = "Browse and dumps messages on a jms queue with optional forwarding of messages"
)
@PicocliScript
import groovy.transform.Field

import java.nio.ByteBuffer
import java.time.Instant

// PicocliBaseScript prints usage help or version if requested by the user

class UserDependent {
    @CommandLine.Option(order = 2, names = ["-u", "--user"], description = "User for AMQ", required = true)
    String user

    @CommandLine.Option(order = 3, names = ["-P", "--password"], description = "Password for AMQ", arity = "0..1", interactive = true)
    char[] password
}

class CredentialDependent {
    @CommandLine.ArgGroup(exclusive = false, multiplicity = '0..1')
    UserDependent userDependent

    @CommandLine.Option(order = 3, names = ["--credential"], description = "Credential file for AMQ - format: @|italic <username>:<password>|@", arity = "1", paramLabel = 'FILE')
    File credentialFile
}

class BrokerDependent {
    @CommandLine.Option(order = 1, names = ["-b", "--broker"], description = "AMQ broker url to query", required = true)
    String brokerUrl

    @CommandLine.ArgGroup(order = 2, exclusive = true, multiplicity = '0..1')
    CredentialDependent credentialDependent

    @CommandLine.Option(order = 4, names = ["-q", "--queue"], description = "The queue to browse", required = true)
    String queue
}

@CommandLine.ArgGroup(exclusive = true, multiplicity = "1")
@Field BrokerOrBashCompletion brokerOrBashCompletion

class BrokerOrBashCompletion {
    @CommandLine.Option(order = 0, names = ["--auto-completion"], description = "Genereate bash-completion script for jms-dude", defaultValue = "false")
    boolean autoCompletion

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "0..1")
    BrokerDependent brokerDependent
}

@CommandLine.ArgGroup(exclusive = false, multiplicity = "0..1")
@Field ForwardDependent forwardDependent

class ForwardDependent {
    @CommandLine.Option(order = 4, names = ["-f", "--forward"], description = "The queue or topic to forward messages to. Format queue://name | topic://name", required = false)
    String forward

    @CommandLine.Option(order = 5, names = ["-s", "--selector"], description = "JMS message selector", required = true)
    String selector
}

@CommandLine.Option(order = 6, names = ["-d", "--delete"], description = "delete processed messages from queue", required = false)
@Field boolean delete

@CommandLine.ArgGroup(exclusive = true, multiplicity = "0..1")
@Field ExclusiveProperties exclusiveProperties

class ExclusiveProperties {
    @CommandLine.Option(order = 6, names = ["-a", "--all"], description = "retrieve all message porperties", required = true)
    boolean allProperties

    @CommandLine.Option(order = 7, names = ["-p", "--properties"], description = ["message properties to retrieve", "property names are treated as regular expressions"], paramLabel = "property name", required = true, split = ',')
    List<String> propertyList
}

@CommandLine.ArgGroup(exclusive = true, multiplicity = "0..1")
@Field ExclusiveOutput output

class JsonOutputConfiguration {

    @CommandLine.Option(order = 11, names = ["-j", "--json"], description = "output in json format", required = true)
    boolean jsonOutput

    @CommandLine.Option(order = 12, names = ["-c", "--body"], description = "dumps the message body (json only)", required = false)
    boolean dumpBody
}

class ExclusiveOutput {
    @CommandLine.Option(order = 9, names = ["-t", "--table"], description = "show messages as table", required = true)
    boolean table

    @CommandLine.Option(order = 10, names = ["--dump"], description = "dump messages", required = true)
    boolean dump

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "0..1")
    JsonOutputConfiguration jsonOutputConfiguration
}

@CommandLine.Parameters(paramLabel = 'FILE', arity = '0..1', description = 'messages to send')
@Field File[] messageFiles

def user
def pwd

// the CommandLine that parsed the args is available as a property
assert this.commandLine.commandName == "jms-dude"

def cli = { -> (CommandLine) this.commandLine }
def ansiString = { String text -> CommandLine.Help.Ansi.AUTO.string(text) }

if (brokerOrBashCompletion.autoCompletion) {

    print AutoComplete.bash(cli().commandName, cli())
    System.exit 0
}
//assert brokerDependent != null

def useCredentialFile = {
    -> !(brokerOrBashCompletion?.brokerDependent?.credentialDependent?.credentialFile == null)
}

def getCredentials = {
    ->
    def file = brokerOrBashCompletion?.brokerDependent?.credentialDependent?.credentialFile
    if(!file.exists()) {
        cli().err.println ansiString("@|bold,red Credential file not found ${file.name}.|@ @|bold,red,italic Expected format <username>:<password>!|@".toString())
        System.exit 1
    }
    def matcher = brokerOrBashCompletion?.brokerDependent?.credentialDependent?.credentialFile.text.trim() =~ /(?<user>[^:]+):(?<pwd>.+)/
    if(matcher.matches()) {
        user = matcher.group('user')
        pwd = matcher.group('pwd').toCharArray()
    } else {
        cli().err.println ansiString("@|bold,red,blink Invalid credential file. @|italic Expected format <username>:<password>!|@|@")
        System.exit 1
    }
}

if(useCredentialFile()) {
    getCredentials()
}

def getUser = {
    if(useCredentialFile) {
        user
    } else {
        brokerOrBashCompletion?.brokerDependent?.credentialDependent?.userDependent?.user
    }
}

def getPassword = {
    if(useCredentialFile) {
        pwd
    } else {
        brokerOrBashCompletion?.brokerDependent?.credentialDependent?.userDependent?.password
    }
}

def selectorAvailable = { -> !(forwardDependent?.selector == null || forwardDependent?.selector?.isBlank()) }

def forwardActive = { -> !(forwardDependent?.forward == null || forwardDependent?.forward?.isBlank()) }
def forwardValid = { -> forwardDependent?.forward ==~ /^(?i)(queue|topic):\/\/(\S)+$/ }

enum FORWARDTYPE { QUEUE, TOPIC }

def forwardType = { ->
    def type = forwardDependent?.forward.find(/^[^:]+/)
    return FORWARDTYPE.valueOf(type.toUpperCase())
}

def forwardDestination = { ->
    return forwardDependent?.forward.find(/[^\/]+$/)
}

def printDestination = {
    destination ->
        if (destination != null) {
            if (destination instanceof Queue) {
                return 'queue://' + destination.queueName
            } else if (destination instanceof Topic) {
                return 'topic://' + destination.topicName
            } else {
                return 'n/a'
            }
        } else return 'n/a'
}

def retrieveProperties = {
    Message msg ->
        def messageProperties = [:]
        if(exclusiveProperties != null) {
            if (exclusiveProperties.allProperties) {
                return msg.properties.collectEntries { [(it.key): (it.value instanceof UTF8Buffer) ? it.value.toString() : it.value] }
            } else if (!exclusiveProperties?.propertyList?.isEmpty()) {
                exclusiveProperties.propertyList.each {
                    pattern ->
                        messageProperties << msg.properties.findAll { property -> property.key ==~ pattern }
                                .collectEntries { [(it.key): (it.value instanceof UTF8Buffer) ? it.value.toString() : it.value] }
                }
                return messageProperties
            }
        }
        return messageProperties
}

def retrieveBody = {
    Message msg ->
        switch (msg.class) {
            case TextMessage:
                return (msg as TextMessage).text
            case BytesMessage:
                msg = (BytesMessage) msg
                ByteBuffer buffer = ByteBuffer.allocate(msg.bodyLength.intValue())
                msg.readBytes(buffer.array())
                return buffer.array().encodeBase64().toString()
            case StreamMessage:
                return (msg as StreamMessage).toString()
            case ObjectMessage:
                return (msg as ObjectMessage).getObject()
            case MapMessage:
                return (msg as MapMessage).mapNames.iterator().collectEntries { it: (msg as MapMessage).getObject(it) }
            case Message:
                break
            default:
                throw new IllegalArgumentException("No vaild jms message " + msg.class.toString())
        }
}

enum OUTPUT {
    TABLE, JSON, DUMP
}

def outputType = { ->
    output == null || output.table ? OUTPUT.TABLE : output?.jsonOutputConfiguration?.jsonOutput ? OUTPUT.JSON : output.dump ? OUTPUT.DUMP : OUTPUT.TABLE }


def outputTable = {
    PrintStream stream, messages, String... header ->
        ShellTable table = new ShellTable().emptyTableText("no messages found")
        table.column("No.").alignRight()
        table.column("JMSMessageId").alignLeft()
        table.column("JMSType").alignLeft()
        table.column("JMSCorrelationId").alignLeft()
        table.column("JMSTimestamp").alignLeft()
        table.column("Properties").alignLeft()

        if(header != null) {
            header.findAll { !(it==null && it?.isBlank()) }.each {
                stream.println it
            }
        }

        messages.eachWithIndex {
            Message message, index ->
                def row = table.addRow()
                row.addContent(++index, message.JMSMessageID, message.JMSType, message.JMSCorrelationID, Instant.ofEpochMilli(message.JMSTimestamp), retrieveProperties(message) )
        }
        table.print(stream)

        stream.println "\n${messages.size()} ${(messages.size() > 1) ? 'messages' : 'message'} selected"
        stream.println ""
}

def outputJson = {
    stream, Collection messages ->
        def json = new JsonBuilder()
        def messageMap = messages.collectEntries {
            Message msg ->
                [(msg): [
                        JMSMessageID    : msg.JMSMessageID,
                        JMSType         : msg.JMSType,
                        JMSDestination  : "${printDestination(msg.JMSDestination)}",
                        JMSCorrelationID: msg.JMSCorrelationID,
                        JMSTimestamp    : msg.JMSTimestamp,
                        JMSTimestampUTC : Instant.ofEpochMilli(msg.JMSTimestamp).toString(),
                        JMSExpiration   : msg.JMSExpiration,
                        JMSPriority     : msg.JMSPriority,
                        JMSReplyTo      : msg.JMSReplyTo,
                        JMSDeliveryMode : msg.JMSDeliveryMode,
                        JMSRedelivered  : msg.JMSRedelivered
                ]]
        }
        messageMap.findAll { !it.key.properties?.isEmpty() }.each {
            it.value << [ Properties: retrieveProperties(it.key) ]
        }
        if(output.jsonOutputConfiguration?.dumpBody) {
            messageMap.each {
                it.value << [ Body: retrieveBody(it.key) ]
            }
        }

        json messageMap.values().collect()
        stream.println json.toPrettyString()
}

def outputDump = {
    stream, messages, String... header ->
        messages.each {
            println it
        }
}

def printOutput = {
    PrintStream outStream, OUTPUT type, messages, String... header ->
        switch (type) {
            case OUTPUT.JSON:
                outputJson(outStream, messages)
                break
            case OUTPUT.DUMP:
                outputDump(outStream, messages, header)
                break
            case OUTPUT.TABLE:
            default:
                outputTable(outStream, messages, header)
                break
        }
}

def copyHeaderAndProperties = {
    Message oldMessage, Message newMessage ->
        def propertiesToCopy = []

        if(exclusiveProperties != null) {
            if(exclusiveProperties.allProperties) {
                propertiesToCopy = oldMessage.properties.collect { it.key }
            } else if (!exclusiveProperties?.propertyList?.isEmpty()) {
                exclusiveProperties.propertyList.each {
                    pattern ->
                        oldMessage.properties.findAll { property -> property.key ==~ pattern }.each {
                            propertiesToCopy << it.key
                        }
                }
            }
        }

        propertiesToCopy.each {
            String property ->
                newMessage.setObjectProperty(property, oldMessage.getObjectProperty(property))
        }
        newMessage.JMSCorrelationID = oldMessage.JMSCorrelationID
        newMessage.JMSDestination = oldMessage.JMSDestination
        newMessage.JMSMessageID = oldMessage.JMSMessageID
        newMessage.JMSReplyTo = oldMessage.JMSReplyTo
        newMessage.JMSType = oldMessage.JMSType

        return newMessage
}

def createCopy = {
    Session session, Message oldMessage ->
        def newMessage
        switch (oldMessage.class) {
            case TextMessage:
                newMessage = session.createTextMessage((oldMessage as TextMessage).text)
                break
            case BytesMessage:
                newMessage = session.createBytesMessage()
                oldMessage = (BytesMessage) oldMessage
                ByteBuffer buffer = ByteBuffer.allocate(oldMessage.bodyLength.intValue())
                oldMessage.readBytes(buffer.array())
                newMessage.writeBytes(buffer.array())
                break
            case StreamMessage:
                newMessage = session.createTextMessage()
                break
            case ObjectMessage:
                newMessage = session.createTextMessage()
                break
            case MapMessage:
                newMessage = session.createTextMessage()
                break
            case Message:
                newMessage = session.createTextMessage()
                break
            default:
                throw new IllegalArgumentException("No vaild jms message " + oldMessage.class.toString())
        }
        return copyHeaderAndProperties(oldMessage, newMessage)
}

new ActiveMQConnectionFactory(brokerURL: brokerOrBashCompletion?.brokerDependent?.brokerUrl)
        .createQueueConnection(getUser(), getPassword().toString()).with {
    start()
    Session session = createSession(true, Session.CLIENT_ACKNOWLEDGE)
    def jmsQueue = session.createQueue(brokerOrBashCompletion?.brokerDependent?.queue)
    QueueBrowser browser = selectorAvailable() ? session.createBrowser(jmsQueue, forwardDependent.selector) : session.createBrowser(jmsQueue)

    def messages = browser.enumeration.iterator().collect()

    printOutput(System.out, outputType(), messages, ansiString("\nSelected messages from @|bold,yellow queue://${jmsQueue.queueName}|@"), (selectorAvailable()) ? "Used selector: ${forwardDependent.selector}\n" : "")

    if (forwardActive()) {
        if (forwardValid()) {
            def forwardedMessages = []

            def destination
            switch (forwardType()) {
                case FORWARDTYPE.QUEUE:
                    destination = session.createQueue(forwardDestination())
                    break
                case FORWARDTYPE.TOPIC:
                    destination = session.createTopic(forwardDestination())
                    break
            }
            def producer = session.createProducer(destination)

            messages.each {
                Message message ->
                    def newMessage = createCopy(session, message)
                    producer.send(newMessage, message.JMSDeliveryMode, message.JMSPriority, message.JMSExpiration)
                    forwardedMessages << newMessage
            }

            if (outputType() == OUTPUT.TABLE) {
                printOutput(System.out, outputType(), forwardedMessages, ansiString("\nForwarded @|bold,white ${forwardedMessages.size()} ${(forwardedMessages.size() > 1) ? 'messages' : 'message'}|@"),
                        ansiString("Destination: @|bold,yellow ${forwardDependent.forward}|@\n"))
            } else {
                cli().err.println ansiString("Forwarded @|bold,white ${forwardedMessages.size()} ${(forwardedMessages.size() > 1) ? 'messages' : 'message'}|@")
            }
        } else {
            cli().err.println "Forward target invalid: ${forwardDependent.forward}"
        }
    }

    if (delete) {
        messages.each {
            Message msg ->
                def messageToDelete = session.createConsumer(jmsQueue, "JMSMessageID = '${msg.JMSMessageID}'").receive(120)
                messageToDelete?.acknowledge()
                cli().err.println "Deleting message: ${messageToDelete?.JMSMessageID}"
        }
    }
    session.commit()
    close()
}

