#!/bin/groovy
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


class BrokerDependent {
    @CommandLine.Option(order = 1, names = ["-b", "--broker"], description = "AMQ broker url to query", required = true)
    String brokerUrl

    @CommandLine.Option(order = 2, names = ["-u", "--user"], description = "User for AMQ")
    String user

    @CommandLine.Option(order = 3, names = ["-P", "--password"], description = "Password for AMQ", arity = "0..1", interactive = true)
    char[] password

    @CommandLine.Option(order = 4, names = ["-q", "--queue"], description = "The queue to browse", required = true)
    String queue
}

@CommandLine.ArgGroup(exclusive = true, multiplicity = "1")
@Field BrokerOrBashCompletion brokerOrBashCompletion

class BrokerOrBashCompletion {
    @CommandLine.Option(order = 0, names = ["--auto-completion"], description = "Genereate bash-completion script for jms-dude - script stops after genereation", defaultValue = "false")
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

// the CommandLine that parsed the args is available as a property
assert this.commandLine.commandName == "jms-dude"

def cli = { -> (CommandLine) this.commandLine }

if (brokerOrBashCompletion.autoCompletion) {

    print AutoComplete.bash(cli().commandName, cli())
    System.exit 0
}

//assert brokerDependent != null


def selectorAvailable = { -> !(forwardDependent?.selector == null || forwardDependent.selector?.isBlank()) }
def forwardActive = { -> !(forwardDependent.forward == null || forwardDependent.forward?.isBlank()) }

enum FORWARDTYPE { QUEUE, TOPIC }

def forwardType = { ->
    def type = forwardDependent.forward.find(/^[^:]+/)
    return FORWARDTYPE.valueOf(type.toUpperCase())
}

def forwardDestination = { ->
    return forwardDependent.forward.find(/[^\/]+$/)
}

enum OUTPUT {
    TABLE, JSON, DUMP
}

def outputType = { ->
    output == null || output.table ? OUTPUT.TABLE : output?.jsonOutputConfiguration?.jsonOutput ? OUTPUT.JSON : output.dump ? OUTPUT.DUMP : OUTPUT.TABLE }


def outputTable = {
    PrintStream stream, messages, String... header ->
        ShellTable table = new ShellTable().emptyTableText("no messges found")
        table.column("JMSMessageId").alignLeft()
        table.column("JMSType").alignLeft()
        table.column("JMSCorrelationId").alignLeft()
        table.column("JMSTimestamp").alignLeft()
        table.column("Properties").alignLeft()

        if(header != null) {
            header.findAll { !it?.isBlank() } each {
                stream.println it
            }
        }

        messages.each {
            Message message ->
                def row = table.addRow()
                row.addContent(message.JMSMessageID, message.JMSType, message.JMSCorrelationID, Instant.ofEpochMilli(message.JMSTimestamp), message.propertyNames.iterator().collect())
        }

        table.print(stream)
        stream.println ""
}

def outputJson = {
    stream, messages ->
        def json = new JsonBuilder()
        def messageMap = messages.collectEntries { [(it.JMSMessageID): it] }
        json messageMap
        json.writeTo(stream)
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
        oldMessage.propertyNames.iterator().each {
            property ->
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
        .createQueueConnection(brokerOrBashCompletion?.brokerDependent?.user, brokerOrBashCompletion?.brokerDependent?.password?.toString()).with {
    start()
    Session session = createSession(false, Session.AUTO_ACKNOWLEDGE)
    def jmsQueue = session.createQueue(brokerOrBashCompletion?.brokerDependent?.queue)
    QueueBrowser browser = selectorAvailable() ? session.createBrowser(jmsQueue, forwardDependent.selector) : session.createBrowser(jmsQueue)

    def messages = browser.enumeration.iterator().collect()

    printOutput(System.out, outputType(), messages, "\nSelected messages from ${queue}\n", (selectorAvailable()) ? "Used selector: ${forwardDependent.selector}" : null)

    if (forwardActive()) {
        def forwardedMessages = []
        messages.each {
            Message message ->
                def newMessage = createCopy(session, message)
                def destination
                switch (forwardType()) {
                    case FORWARDTYPE.QUEUE:
                        destination = session.createQueue(forwardDestination())
                        break
                    case FORWARDTYPE.TOPIC:
                        destination = session.createTopic(forwardDestination())
                        break
                }
                session.createProducer(destination).send(newMessage, message.JMSDeliveryMode, message.JMSPriority, message.JMSExpiration)
                forwardedMessages << newMessage
        }
        printOutput(System.out, outputType(), forwardedMessages, "\nForwarded messages\n", "Destination: ${forwardDependent.forward}")
    }
    close()
}

