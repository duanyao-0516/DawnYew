package com.dawnyew.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OperationOutcome(val operationId:String,val commandType:String,val resultIds:List<String>)
data class PendingOperationRecovery(val operationId:String,val outcome:OperationOutcome?)
internal val noPendingOperation:StateFlow<PendingOperationRecovery?> = MutableStateFlow<PendingOperationRecovery?>(null).asStateFlow()
