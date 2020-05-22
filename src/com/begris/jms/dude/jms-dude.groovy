#!/bin/groovy
package com.begris.jms.dude

import groovy.json.JsonBuilder
@Grab('info.picocli:picocli-groovy:4.3.2')
@GrabConfig(systemClassLoader=true)
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

@CommandLine.Option(names = ["-s", "--selector"], description = "JMS message selector")
@Field String selector

@CommandLine.ArgGroup(exclusive = true, multiplicity = "0..1")
@Field Exclusive output

class Exclusive {
    @CommandLine.Option(names = ["-t", "--table"], description = "show messages as table", required = true)
    boolean table

    @CommandLine.Option(names = ["-d", "--dump"], description = "dump messages", required = true)
    boolean dump

    @CommandLine.Option(names = ["-j", "--json"], description = "output in json format", required = true)
    boolean jsonOutput
}

@CommandLine.Option(names = ["-c", "--count"], description = "number of repetitions")
@Field int count = 1;


count.times {
    println "hi"
}
// the CommandLine that parsed the args is available as a property
assert this.commandLine.commandName == "jms-dude"

println queue
println selector

def selectorAvailable = { -> !(selector == null || selector?.isBlank()) }

enum OUTPUT { TABLE, JSON, DUMP }
def outputType = { -> output == null || output.table ? OUTPUT.TABLE : output.jsonOutput ? OUTPUT.JSON : output.dump ? OUTPUT.DUMP : OUTPUT.TABLE }


def outputTable = {
    stream, messages ->
        ShellTable table = new ShellTable().emptyTableText("no messges found")
        table.column("JMSMessageId").alignLeft()
        table.column("JMSType").alignLeft()
        table.column("JMSCorrelationId").alignLeft()
        table.column("JMSTimestamp").alignLeft()
        table.column("Properties").alignLeft()

        if (selectorAvailable()) println "Used selector: ${selector}"

        messages.each {
            Message message ->
                def row = table.addRow()
                row.addContent(message.JMSMessageID, message.JMSType, message.JMSCorrelationID, Instant.ofEpochMilli(message.JMSTimestamp), message.propertyNames.iterator().collect())
        }

        table.print(stream)
}

def outputJson = {
    stream, messages ->
        def json = new JsonBuilder()
        def messageMap =  messages.collectEntries { [(it.JMSMessageID): it] }
        json messageMap
        json.writeTo(stream)
}

def outputDump = {
    messages ->
        println it
}


new ActiveMQConnectionFactory(brokerURL: brokerUrl).createQueueConnection(user, password).with {
    start()
    QueueSession session = createQueueSession(false, Session.AUTO_ACKNOWLEDGE)
    def jmsQueue = session.createQueue(queue)
    QueueBrowser browser = selectorAvailable() ? session.createBrowser(jmsQueue, selector) : session.createBrowser(jmsQueue)

//    ShellTable table = new ShellTable().emptyTableText("no messges found")
//    table.column("JMSMessageId").alignLeft()
//    table.column("JMSType").alignLeft()
//    table.column("JMSCorrelationId").alignLeft()
//    table.column("JMSTimestamp").alignLeft()
//    table.column("Properties").alignLeft()
//
//    if (selectorAvailable()) println "Used selector: ${browser.messageSelector}"

//    println output.properties

    def messages = browser.enumeration.iterator()

    switch (outputType()) {
        case OUTPUT.TABLE:
            println "table"
            outputTable(System.out, messages)
            break
        case OUTPUT.JSON:
            println "json"
            outputJson(System.out, messages)
            break
        case OUTPUT.DUMP:
            println "dump"
            outputTable(System.out, messages)
            break
    }

//
//    browser.enumeration.iterator().each {
//        Message message ->
//            def row = table.addRow()
//            row.addContent(message.JMSMessageID, message.JMSType, message.JMSCorrelationID, Instant.ofEpochMilli(message.JMSTimestamp), message.propertyNames.iterator().collect())
//    }



//    table.print(System.out)
//
//    list.each() {
//        entry ->
//            println entry
//            TextMessage message = session.createTextMessage('Employee '+entry)
//            message.setJMSType('EMPLOYEE_MASTERDATA_V1.0.0')
//            message.setJMSDeliveryMode(DeliveryMode.PERSISTENT)
//            message.setJMSReplyTo(session.createTemporaryTopic())
//            message.setJMSCorrelationID(UUID.randomUUID().toString())
//            message.setLongProperty('EMP_ID', entry)
//
//            publisher.publish(message)
//    }
    close()
}

