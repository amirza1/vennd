/**
 * Created by whoisjeremylam on 20/03/14.
 */

@Grab(group='log4j', module='log4j', version='1.2.17')
@Grab(group='org.xerial', module='sqlite-jdbc', version='3.7.2')

//@Grapes([
//@Grab(group='org.xerial', module='sqlite-jdbc', version='3.7.2'),
//@GrabConfig(systemClassLoader=true)
//])

import org.apache.log4j.*
import groovy.sql.Sql

class CounterpartyFollower {
    static logger
    static log4j
    static counterpartyAPI
    static bitcoinAPI
    static satoshi = 100000000
    static int inceptionBlock
//    static BigDecimal feeAmountPercentage
//    static BigDecimal txFee
    static assetConfig
//    static String listenerBitcoinAddress
    static boolean testMode
    static int confirmationsRequired
    static int sleepIntervalms
    static String databaseName
    static db

    public class Asset {
        def String counterpartyAssetName
        def String nativeAssetName
        def String counterpartyAddress // the counterparty/bitcoin address side where we will receive the Counterparty asset
        def String nativeAddress // The address which users should send their native asset to
        def BigDecimal txFee
        def BigDecimal feePercentage
        def int inceptionBlock
        def boolean mappingRequired

        public Asset(String counterpartyAssetNameValue, String nativeAssetNameValue, String counterpartyAddressValue, String nativeAddressValue, BigDecimal txFeeValue, BigDecimal feePercentageValue, boolean mappingRequiredValue) {
            counterpartyAssetName = counterpartyAssetNameValue
            nativeAssetName = nativeAssetNameValue
            counterpartyAddress = counterpartyAddressValue
            nativeAddress = nativeAddressValue
            txFee = txFeeValue
            feePercentage = feePercentageValue
            mappingRequired = mappingRequiredValue
        }
    }

    public class Payment {
        def String inAsset
        def Long currentBlock
        def String txid
        def String sourceAddress
        def String destinationAddress
        def String outAsset
        def Long outAmount
        def Long lastModifiedBlockId
        def String status

        public Payment(String inAssetValue, Long currentBlockValue, String txidValue, String sourceAddressValue, String destinationAddressValue, String outAssetValue, Long outAmountValue, Long lastModifiedBlockIdValue, Long originalAmount) {
            def row
            inAsset = inAssetValue
            outAsset = outAssetValue
            outAmount = outAmountValue
            currentBlock = currentBlockValue
            txid = txidValue
            lastModifiedBlockId = lastModifiedBlockIdValue
            def assetConfigIndex = assetConfig.findIndexOf { a ->
                a.counterpartyAssetName == inAssetValue
            }
            def mappingRequired = assetConfig[assetConfigIndex].mappingRequired

            if (originalAmount <= 10000000) {
                status = 'authorized'
            }
            else {
                status = 'valid'
            }

            // Check if the send was performed TO an address registered via the API
            // If it was then payment should be swept into the central address
            row = db.firstRow("select * from addressMaps where counterpartyPaymentAddress = ${destinationAddressValue}")
            if (row != null) {
                sourceAddress = destinationAddressValue
                destinationAddress = assetConfig[assetConfigIndex].counterpartyAddress
                outAsset = inAssetValue
            }
            else {
                // Check if the send was performed FROM an address registered via the API to the central address
                // If it was then payment should be made from the central address to the external address
                row = db.firstRow ("select * from addressMaps where counterpartyPaymentAddress = ${sourceAddressValue}")
                if (row != null) {
                    sourceAddress = assetConfig[assetConfigIndex].nativeAddress
                    destinationAddress = row.externalAddress
                    outAsset = assetConfig[assetConfigIndex].nativeAssetName

                    assert outAsset == assetConfig[assetConfigIndex].nativeAssetName
                }
                else {
                    // someone sent directly to the central address but address mapping is required. We don't know who to pay out to
                    if (mappingRequired == true && assetConfig[assetConfigIndex].counterpartyAddress) {
                        sourceAddress = assetConfig[assetConfigIndex].nativeAddress
                        destinationAddress = assetConfig[assetConfigIndex].nativeAddress
                        outAsset = assetConfig[assetConfigIndex].nativeAssetName
                        outAmount = 0
                    }
                    // vanilla send - send from the central address the native equivalent to the address which sent this asset
                    else {
                        sourceAddress = assetConfig[assetConfigIndex].nativeAddress
                        destinationAddress = sourceAddressValue
                        outAsset = assetConfig[assetConfigIndex].nativeAssetName

                        assert outAsset == assetConfig[assetConfigIndex].nativeAssetName
                    }
                }
            }
        }
    }

    public init() {
        counterpartyAPI = new CounterpartyAPI()
        bitcoinAPI = new BitcoinAPI("CounterpartyBitcoinAPI.ini")

        // Set up some log4j stuff
        logger = new Logger()
        PropertyConfigurator.configure("CounterpartyFollower_log4j.properties")
        log4j = logger.getRootLogger()

        // Read in ini file
        def iniConfig = new ConfigSlurper().parse(new File("CounterpartyFollower.ini").toURL())
        inceptionBlock = iniConfig.inceptionBlock
        testMode = iniConfig.testMode
        sleepIntervalms = iniConfig.sleepIntervalms
        databaseName = iniConfig.database.name
        confirmationsRequired = iniConfig.confirmationsRequired

        assetConfig = []
        iniConfig.asset.each { it ->
            def currentAsset = new Asset(it.value.counterpartyAssetName, it.value.nativeAssetName, it.value.counterpartyAddress, it.value.nativeAddress, it.value.txFee, it.value.feePercentage, it.value.mappingRequired)
            assetConfig.add(currentAsset)
        }

        // init database
        def row
        db = Sql.newInstance("jdbc:sqlite:${databaseName}", "org.sqlite.JDBC")
        db.execute("PRAGMA busy_timeout = 1000")
        db.execute("create table if not exists counterpartyBlocks (blockId integer, status string, duration integer)")
        db.execute("create unique index if not exists counterpartyBlocks1 on counterpartyBlocks(blockId)")

        // Check vital tables exist
        row = db.firstRow("select name from sqlite_master where type='table' and name='blocks'")
        assert row != null
        row = db.firstRow("select name from sqlite_master where type='table' and name='transactions'")
        assert row != null
        row = db.firstRow("select name from sqlite_master where type='table' and name='inputAddresses'")
        assert row != null
        row = db.firstRow("select name from sqlite_master where type='table' and name='outputAddresses'")
        assert row != null
        row = db.firstRow("select name from sqlite_master where type='table' and name='fees'")
        assert row != null
        row = db.firstRow("select name from sqlite_master where type='table' and name='audit'")
        assert row != null
        row = db.firstRow("select name from sqlite_master where type='table' and name='credits'")
        assert row != null
        row = db.firstRow("select name from sqlite_master where type='table' and name='debits'")
        assert row != null
        row = db.firstRow("select name from sqlite_master where type='table' and name='counterpartyBlocks'")
        assert row != null
        row = db.firstRow("select name from sqlite_master where type='table' and name='addressMaps'")
        assert row != null
    }

    public Audit() {

    }


    public processSeenBlock(currentBlock) {
        log4j.info("Block ${currentBlock}: seen")

        db.execute("insert into counterpartyBlocks values (${currentBlock}, 'seen', 0)")
    }


    public lastProcessedBlock() {
        def Long result

        def row = db.firstRow("select max(blockId) from counterpartyBlocks where status in ('processed','error')")

        assert row != null

        if (row[0] == null) {
            db.execute("insert into counterpartyBlocks values(${inceptionBlock}, 'processed', 0)")
            result = inceptionBlock
        }
        else {
            result = row[0]
        }

        return result
    }


    public lastBlock() {
        def Long result

        def row = db.firstRow("select max(blockId) from counterpartyBlocks")

        assert row != null

        if (row[0] == null) {
            result = inceptionBlock
        }
        else {
            result = row[0]
        }

        return result
    }




    public processBlock(Long currentBlock) {
        def timeStart
        def timeStop
        def duration
        def sends

        timeStart = System.currentTimeMillis()
        sends = counterpartyAPI.getSends(currentBlock)

        log4j.info("Block ${currentBlock}: processing " + sends.size() + " sends")
        def transactions = []
        for (send in sends) {
            assert send instanceof HashMap

            def inputAddresses = []
            def outputAddresses = []
            def inAsset = ""
            def Long inAmount = 0
            def outAsset = ""
            def Long outAmount = 0
            def fee = 0.0
            def txFee = 0.0
            def txid = ""
            def source = send.source
            def destination = send.destination
            def serviceAddress = "" // the service address which was sent to

            // Check if the send was performed to the central service listen to any of the central asset addresses we are listening on
            def notFound = true
            def counter = 0
            while (notFound && counter <= assetConfig.size()-1) {
                if (send.source != assetConfig[counter].counterpartyAddress && send.destination == assetConfig[counter].counterpartyAddress) {
                    notFound = false
                    inAsset = assetConfig[counter].counterpartyAssetName
                    outAsset = assetConfig[counter].nativeAssetName
                    fee = assetConfig[counter].feePercentage
                    txFee = assetConfig[counter].txFee
                    serviceAddress = assetConfig[counter].counterpartyAddress
                }

                counter++
            }

            // Check if a send was performed to an address registered via the API
            def row = db.firstRow("select * from addressMaps where counterpartyPaymentAddress = ${destination}")
            if (row != null) {
                notFound = false
                inAsset = row.counterpartyAssetName
                outAsset = row.nativeAssetName
                def assetConfigIndex = assetConfig.findIndexOf { a ->
                    a.counterpartyAssetName == send.asset
                }

                assert inAsset == assetConfig[assetConfigIndex].counterpartyAssetName
                assert outAsset == assetConfig[assetConfigIndex].nativeAssetName
                fee = assetConfig[assetConfigIndex].feePercentage
                txFee = assetConfig[assetConfigIndex].txFee
                serviceAddress = row.counterpartyPaymentAddress
            }

            // Record the send
            if (notFound == false) {
                txid = send.tx_hash
                inAmount = send.quantity

                // Calculate fee
                def amountMinusTX
                def calculatedFee

                // Remove the TX Fee first from calculations
                amountMinusTX = inAmount - (txFee * satoshi)

                // If the amount that was sent was less than the cost of TX then eat the whole amount
                if (amountMinusTX < 0) {
                    amountMinusTX = 0
                }

                if (amountMinusTX > 0) {
                    calculatedFee = ((amountMinusTX * fee / 100) + (txFee * satoshi)).toInteger()

                    if (inAmount < calculatedFee) {
                        calculatedFee = inAmount
                    }
                }
                else {
                    calculatedFee = inAmount
                }

                // Set out amount if it is more than a satoshi
                if (inAmount - calculatedFee >= 1) {
                    outAmount = inAmount - calculatedFee
                }
                else {
                    outAmount = 0
                }

                assert inAmount == outAmount + calculatedFee  // conservation of energy

                inputAddresses.add(source)
                outputAddresses.add(destination)

                transactions.add([txid, inputAddresses, outputAddresses, inAmount, inAsset, outAmount, outAsset, calculatedFee, serviceAddress])
                log4j.info("Block ${currentBlock} found service call: ${currentBlock} ${txid} ${inputAddresses} ${serviceAddress} ${inAmount/satoshi} ${inAsset} -> ${outAmount/satoshi} ${outAsset} (${calculatedFee/satoshi} ${inAsset} fee collected)")
            }
        }

        // Insert into DB the transaction along with the order details
        db.execute("begin transaction")
        try {
            for (transaction in transactions) {
                def String txid = transaction[0]
                def String inputAddress = transaction[1][0]            // pick the first input address if there is more than 1
                def String serviceAddress = transaction[8]  // take the service address as the address that was sent to
                def Long inAmount = transaction[3]
                def inAsset = transaction[4]
                def Long outAmount = transaction[5]
                def String outAsset = transaction[6]
                def feeAmount = transaction[7]
                def feeAsset = inAsset

                // int currentBlockValue, String txidValue, String sourceAddressValue, String destinationAddressValue, String outAssetValue, String outAmountValue, int lastModifiedBlockIdValue, int originalAmount
                // there will only be 1 output for counterparty assets but not the case for native assets - ie change
                // form a payment object which will determine the payment direction and source and destination addresses
                // public Payment(int currentBlockValue, String txidValue, String sourceAddressValue, String destinationAddressValue, String outAssetValue, Long outAmountValue, int lastModifiedBlockIdValue, Long originalAmount)
                def payment = new Payment(inAsset, currentBlock, txid, inputAddress, serviceAddress, outAsset, outAmount, currentBlock, inAmount)

                log4j.info("insert into transactions values (${currentBlock}, ${txid})")
                db.execute("insert into transactions values (${currentBlock}, ${txid})")
                for (eachInputAddress in transaction[1]) {
                    log4j.info("insert into inputAddresses values (${txid}, ${eachInputAddress})")
                    db.execute("insert into inputAddresses values (${txid}, ${eachInputAddress})")
                }
                for (outputAddress in transaction[2]) {
                    log4j.info("insert into outputAddresses values (${txid}, ${outputAddress})")
                    db.execute("insert into outputAddresses values (${txid}, ${outputAddress})")
                }

                log4j.info("insert into credits values (${currentBlock}, ${txid}, ${inputAddress}, ${serviceAddress}, ${inAsset}, ${inAmount}, ${outAsset}, ${outAmount}, 'valid')")
                db.execute("insert into credits values (${currentBlock}, ${txid}, ${inputAddress}, ${serviceAddress}, ${inAsset}, ${inAmount}, ${outAsset}, ${outAmount}, 'valid')")
                log4j.info("insert into fees values (${currentBlock}, ${txid}, ${feeAsset}, ${feeAmount})")
                db.execute("insert into fees values (${currentBlock}, ${txid}, ${feeAsset}, ${feeAmount} )")
                if (outAmount > 0) {
                    log4j.info("insert into payments values (${payment.currentBlock}, ${payment.txid}, ${payment.sourceAddress}, ${payment.destinationAddress}, ${payment.outAsset}, ${payment.outAmount}, ${payment.status}, ${payment.lastModifiedBlockId})")
                    db.execute("insert into payments values (${payment.currentBlock}, ${payment.txid}, ${payment.sourceAddress}, ${payment.destinationAddress}, ${payment.outAsset}, ${payment.outAmount}, ${payment.status}, ${payment.lastModifiedBlockId})")
                }
            }
            db.execute("commit transaction")
        } catch (Throwable e) {
            db.execute("update counterpartyBlocks set status='error', duration=0 where blockId = ${currentBlock}")
            log4j.info("Block ${currentBlock}: error")
            log4j.info("Exception: ${e}")
            db.execute("rollback transaction")
            assert e == null
        }

        timeStop = System.currentTimeMillis()
        duration = (timeStop-timeStart)/1000
        db.execute("update counterpartyBlocks set status='processed', duration=${duration} where blockId = ${currentBlock}")
        log4j.info("Block ${currentBlock}: processed in ${duration}s")

    }

    public static int main(String[] args) {
        def counterpartyFollower = new CounterpartyFollower()

        counterpartyFollower.init()
        counterpartyFollower.Audit()

        log4j.info("counterpartyd follower started")
        log4j.info("Last processed block: " + counterpartyFollower.lastProcessedBlock())
        log4j.info("Last seen block: " + counterpartyFollower.lastBlock())

        // Begin following blocks
        while (true) {
            def blockHeight = bitcoinAPI.getBlockHeight()
            def currentBlock = counterpartyFollower.lastBlock()
            def currentProcessedBlock = counterpartyFollower.lastProcessedBlock()

            // If the current block is less than the last block we've seen then add it to the blocks db
            while (counterpartyFollower.lastBlock() < blockHeight) {
                currentBlock++

                counterpartyFollower.processSeenBlock(currentBlock)
            }

            // Check if we can process a block
            while (counterpartyFollower.lastProcessedBlock() < currentBlock - 3) {
                currentProcessedBlock++

                counterpartyFollower.processBlock(currentProcessedBlock)

                currentProcessedBlock = counterpartyFollower.lastProcessedBlock()
            }

            sleep(sleepIntervalms)
        }

    }
}
