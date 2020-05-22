#!/bin/groovy
package com.begris.jms.dude

import groovy.json.JsonBuilder
@Grab('info.picocli:picocli-groovy:4.3.2')
@GrabConfig(systemClassLoader = true)
import picocli.CommandLine
import picocli.groovy.PicocliScript

@Grab('org.apache.karaf.shell:org.apache.karaf.shell.table:4.2.8')
import org.apache.karaf.shell.table.*

@Grab('org.apache.activemq:activemq-all:5.15.12')
import org.apache.activemq.ActiveMQConnectionFactory
import javax.jms.*

@CommandLine.Command(name = "jms-dude",
        mixinStandardHelpOptions = true, // add --help and --version options
        description = "@|bold Groovy script|@ @|underline jms-dude|@",
        version = '1.0.0-Snapshot'
)
@PicocliScript
import groovy.transform.Field

import java.nio.ByteBuffer
import java.time.Instant

// PicocliBaseScript prints usage help or version if requested by the user

@CommandLine.Option(names = ["-b", "--broker"], description = "AMQ broker url to query", required = true)
@Field String brokerUrl

@CommandLine.Option(names = ["-u", "--user"], description = "User for AMQ")
@Field String user

@CommandLine.Option(names = ["-p", "--password"], description = "Password for AMQ", interactive = true)
@Field char[] password

@CommandLine.Option(names = ["-q", "--queue"], description = "The queue to browse", required = true)
@Field String queue

@CommandLine.ArgGroup(exclusive = false, multiplicity = "0..1")
@Field ForwardDependent forwardDependent

class ForwardDependent {
    @CommandLine.Option(names = ["-f", "--forward"], description = "The queue or topic to forward messages to. Format queue://name | topic://name", required = false)
    String forward

    @CommandLine.Option(names = ["-s", "--selector"], description = "JMS message selector", required = true)
    String selector
}

@CommandLine.ArgGroup(exclusive = true, multiplicity = "0..1")
@Field ExclusiveOutput output

class ExclusiveOutput {
    @CommandLine.Option(names = ["-t", "--table"], description = "show messages as table", required = true)
    boolean table

    @CommandLine.Option(names = ["-d", "--dump"], description = "dump messages", required = true)
    boolean dump

    @CommandLine.Option(names = ["-j", "--json"], description = "output in json format", required = true)
    boolean jsonOutput
}

// the CommandLine that parsed the args is available as a property
assert this.commandLine.commandName == "jms-dude"

def selectorAvailable = { -> !(forwardDependent.selector == null || forwardDependent.selector?.isBlank()) }

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
    output == null || output.table ? OUTPUT.TABLE : output.jsonOutput ? OUTPUT.JSON : output.dump ? OUTPUT.DUMP : OUTPUT.TABLE }


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

new ActiveMQConnectionFactory(brokerURL: brokerUrl).createQueueConnection(user, password.toString()).with {
    start()
    Session session = createSession(false, Session.AUTO_ACKNOWLEDGE)
    def jmsQueue = session.createQueue(queue)
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

