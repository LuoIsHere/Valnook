package dev.valnook.data

import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.Direction
import dev.valnook.domain.repository.CreateInvestmentPosition
import dev.valnook.domain.repository.FinancialCommands
import dev.valnook.domain.repository.RecordInvestmentTrade
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveAssetType
import dev.valnook.domain.repository.SaveInstrument
import java.util.UUID

internal fun testOperationId():String=UUID.randomUUID().toString()

internal suspend fun FinancialCommands.testAccount(name:String="A",note:String=""):Long=
    execute(SaveAccount(testOperationId(),null,null,name,note,emptyList())).id

internal suspend fun FinancialCommands.testType(name:String="类型"):Long=
    execute(SaveAssetType(testOperationId(),null,name)).id

internal suspend fun FinancialCommands.testInstrument(name:String,symbol:String,typeId:Long,
    currencyCode:String,currentPriceE8:Long):Long=execute(SaveInstrument(testOperationId(),null,null,
    name,symbol,typeId,currencyCode,currentPriceE8/1_000L)).id

internal suspend fun FinancialCommands.testInvestment(accountId:Long,name:String,symbol:String,typeId:Long,
    currencyCode:String,quantityE8:Long,currentPriceE8:Long,costPriceE8:Long?):Long {
    val instrumentId=testInstrument(name,symbol,typeId,currencyCode,currentPriceE8)
    val positionId = execute(CreateInvestmentPosition(testOperationId(), accountId, instrumentId)).id
    if (quantityE8 > 0) execute(RecordInvestmentTrade(testOperationId(), positionId, Direction.BUY,
        quantityE8, requireNotNull(costPriceE8), Long.MIN_VALUE, false))
    return positionId
}

internal suspend fun FinancialCommands.testUpdatePrice(database:ValnookDatabase,positionId:Long,
    currentPriceE8:Long) {
    val position=requireNotNull(database.positions().investment(positionId))
    val instrument=requireNotNull(database.instruments().instrument(position.instrument_id))
    execute(SaveInstrument(testOperationId(),instrument.id,instrument.revision,instrument.name,
        instrument.symbol,instrument.asset_type_id,instrument.currency_code,currentPriceE8/1_000L))
}
