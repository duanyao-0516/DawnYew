package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditorDimensionTest {
    private class Environment:DomainEnvironment {
        private var n=0
        override fun id()="00000000-0000-4000-8000-${(++n).toString().padStart(12,'0')}"
        override fun localNow()="2026-10-24T12:00:00"
        override fun utcNow()="2026-10-24T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    private class Repo(val env:Environment):LedgerRepository {
        val engine=DomainEngine(env)
        override val state=MutableStateFlow<LedgerState?>(engine.initialState())
        var gate:CompletableDeferred<Unit>?=null
        var failure=false
        override suspend fun initialize()=Unit
        override suspend fun execute(command:Command,operationId:String,expectedVersion:Long?):List<String> {
            gate?.await();if(failure) error("fixture")
            val result=engine.apply(state.value!!,command,operationId,expectedVersion);state.value=result.state;return result.resultIds
        }
        override suspend fun snapshot()=state.value!!
        override suspend fun replace(state:LedgerState) {this.state.value=state}
        override suspend fun close()=Unit
    }
    @Test fun allNewDimensionsAttachToExactDraftWhileOrdinaryInputStaysBlocked()=runTest {
        val env=Environment();val repo=Repo(env);val app=AppController(repo,env,backgroundScope);runCurrent()
        val ledger=repo.state.value!!.ledgers.single().id;app.beginEditor(ledger)
        val draftId=app.editor.value!!.operationId
        DimensionKind.entries.forEach {kind->
            val d=Dimension(env.id(),ledger,kind,"新${kind.name}");repo.gate=CompletableDeferred()
            var callback=false;app.createEditorDimension(draftId,d,env.id()) {callback=true};runCurrent()
            app.changeEditor(draftId,EditorChange.Note("must not change"));assertEquals("",app.editor.value!!.note)
            repo.gate!!.complete(Unit);runCurrent();assertTrue(callback)
            val saved=app.editor.value!!
            when(kind){DimensionKind.PROJECT->assertEquals(d.id,saved.projectId);DimensionKind.MERCHANT->assertEquals(d.id,saved.merchantId);DimensionKind.TAG->assertTrue(d.id in saved.tagIds)}
        }
        assertTrue(app.editor.value!!.dirty);assertFalse(app.ui.value.busy)
    }
    @Test fun failedCreationAndRevokedDraftNeverGetAssociation()=runTest {
        val env=Environment();val repo=Repo(env);val app=AppController(repo,env,backgroundScope);runCurrent()
        val ledger=repo.state.value!!.ledgers.single().id;app.beginEditor(ledger)
        val d=Dimension(env.id(),ledger,DimensionKind.PROJECT,"项目")
        repo.failure=true;app.createEditorDimension(app.editor.value!!.operationId,d,env.id());runCurrent();assertNull(app.editor.value!!.projectId)
        repo.failure=false;repo.gate=CompletableDeferred();app.createEditorDimension(app.editor.value!!.operationId,d,env.id());runCurrent()
        app.resetTransientState();repo.gate!!.complete(Unit);runCurrent();assertNull(app.editor.value)
    }
}
