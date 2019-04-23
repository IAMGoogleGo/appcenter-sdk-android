/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.data;

import com.microsoft.appcenter.http.HttpException;
import com.microsoft.appcenter.data.exception.DataException;
import com.microsoft.appcenter.data.models.DataStoreEventListener;
import com.microsoft.appcenter.data.models.DocumentMetadata;
import com.microsoft.appcenter.data.models.PendingOperation;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;

import static com.microsoft.appcenter.http.DefaultHttpClient.METHOD_DELETE;
import static com.microsoft.appcenter.http.DefaultHttpClient.METHOD_POST;
import static com.microsoft.appcenter.data.Constants.PENDING_OPERATION_CREATE_VALUE;
import static com.microsoft.appcenter.data.Constants.PENDING_OPERATION_DELETE_VALUE;
import static com.microsoft.appcenter.data.Constants.PENDING_OPERATION_REPLACE_VALUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.verifyNoMoreInteractions;

public class NetworkStateChangeDataTest extends AbstractDataTest {

    @Before
    public void setUpAuth() {
        setUpAuthContext();
    }

    @Mock
    private DataStoreEventListener mDataStoreEventListener;

    @Test
    public void pendingCreateOperationSuccess() throws JSONException {
        verifyPendingCreateOperationsSuccess(false);
    }

    @Test
    public void pendingCreateOperationSuccessDeletesExpiredOperation() throws JSONException {
        verifyPendingCreateOperationsSuccess(true);
    }

    private void verifyPendingCreateOperationsSuccess(boolean operationExpired) throws JSONException {
        long expirationTime = operationExpired ? PAST_TIMESTAMP : FUTURE_TIMESTAMP;
        final PendingOperation pendingOperation = new PendingOperation(
                USER_TABLE_NAME,
                PENDING_OPERATION_CREATE_VALUE,
                RESOLVED_USER_PARTITION,
                DOCUMENT_ID,
                "document",
                expirationTime,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP);
        when(mLocalDocumentStorage.getPendingOperations(USER_TABLE_NAME)).thenReturn(
                new ArrayList<PendingOperation>() {{
                    add(pendingOperation);
                }});
        ArgumentCaptor<DocumentMetadata> documentMetadataArgumentCaptor = ArgumentCaptor.forClass(DocumentMetadata.class);

        Data.setDataStoreRemoteOperationListener(mDataStoreEventListener);
        mData.onNetworkStateUpdated(true);

        verifyTokenExchangeToCosmosDbFlow(null, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_POST, COSMOS_DB_DOCUMENT_RESPONSE_PAYLOAD, null);

        verify(mDataStoreEventListener).onDataStoreOperationResult(
                eq(PENDING_OPERATION_CREATE_VALUE),
                documentMetadataArgumentCaptor.capture(),
                isNull(DataException.class));
        DocumentMetadata documentMetadata = documentMetadataArgumentCaptor.getValue();
        assertNotNull(documentMetadata);
        verifyNoMoreInteractions(mDataStoreEventListener);

        assertEquals(DOCUMENT_ID, documentMetadata.getDocumentId());
        assertEquals(RESOLVED_USER_PARTITION, documentMetadata.getPartition());
        assertEquals(ETAG, documentMetadata.getETag());
        if (operationExpired) {

            /* Verify operation is deleted from the cache when operation expired. */
            ArgumentCaptor<String> tableNameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> partitionCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> documentIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(mLocalDocumentStorage).deleteOnline(tableNameCaptor.capture(), partitionCaptor.capture(), documentIdCaptor.capture());
            assertEquals(USER_TABLE_NAME, tableNameCaptor.getValue());
            assertEquals(RESOLVED_USER_PARTITION, partitionCaptor.getValue());
            assertEquals(DOCUMENT_ID, documentIdCaptor.getValue());
        } else {

            /* Verify operation is updated in the cache when operation is not expired. */
            ArgumentCaptor<PendingOperation> pendingOperationCaptor = ArgumentCaptor.forClass(PendingOperation.class);
            verify(mLocalDocumentStorage).updatePendingOperation(pendingOperationCaptor.capture());
            PendingOperation capturedOperation = pendingOperationCaptor.getValue();
            assertNotNull(capturedOperation);
            assertEquals(ETAG, capturedOperation.getETag());
            assertEquals(COSMOS_DB_DOCUMENT_RESPONSE_PAYLOAD, capturedOperation.getDocument());
            assertNull(capturedOperation.getOperation());
        }
        verifyNoMoreInteractions(mHttpClient);
    }

    @Test
    public void pendingCreateOperationSuccessWithNoListener() throws JSONException {
        final PendingOperation pendingOperation = new PendingOperation(
                USER_TABLE_NAME,
                PENDING_OPERATION_CREATE_VALUE,
                RESOLVED_USER_PARTITION,
                DOCUMENT_ID,
                "document",
                FUTURE_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP);
        when(mLocalDocumentStorage.getPendingOperations(USER_TABLE_NAME)).thenReturn(
                new ArrayList<PendingOperation>() {{
                    add(pendingOperation);
                }});

        mData.onNetworkStateUpdated(true);
        verifyTokenExchangeToCosmosDbFlow(null, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_POST, COSMOS_DB_DOCUMENT_RESPONSE_PAYLOAD, null);

        ArgumentCaptor<PendingOperation> pendingOperationCaptor = ArgumentCaptor.forClass(PendingOperation.class);
        verify(mLocalDocumentStorage).updatePendingOperation(pendingOperationCaptor.capture());
        PendingOperation capturedOperation = pendingOperationCaptor.getValue();
        assertNotNull(capturedOperation);
        assertEquals(pendingOperation, capturedOperation);
        assertEquals(ETAG, capturedOperation.getETag());
        assertEquals(COSMOS_DB_DOCUMENT_RESPONSE_PAYLOAD, capturedOperation.getDocument());

        verifyNoMoreInteractions(mHttpClient);
        verifyZeroInteractions(mDataStoreEventListener);
    }

    @Test
    public void pendingReplaceOperationWithCosmosDb500Error() throws JSONException {
        verifyPendingOperationFailure(PENDING_OPERATION_REPLACE_VALUE, METHOD_POST, null);
    }

    @Test
    public void pendingDeleteOperationWithCosmosDb500Error() throws JSONException {
        verifyPendingOperationFailure(PENDING_OPERATION_DELETE_VALUE, METHOD_DELETE, DOCUMENT_ID);
    }

    private void verifyPendingOperationFailure(String operation, String cosmosDbMethod, String documentId) throws JSONException {
        final String document = "document";
        final PendingOperation pendingOperation =
                new PendingOperation(
                        USER_TABLE_NAME,
                        operation,
                        RESOLVED_USER_PARTITION,
                        DOCUMENT_ID,
                        document,
                        FUTURE_TIMESTAMP,
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP);
        when(mLocalDocumentStorage.getPendingOperations(USER_TABLE_NAME)).thenReturn(
                new ArrayList<PendingOperation>() {{
                    add(pendingOperation);
                }});
        ArgumentCaptor<DataException> documentErrorArgumentCaptor = ArgumentCaptor.forClass(DataException.class);

        Data.setDataStoreRemoteOperationListener(mDataStoreEventListener);
        mData.onNetworkStateUpdated(true);

        HttpException cosmosFailureException = new HttpException(500, "You failed!");
        verifyTokenExchangeToCosmosDbFlow(documentId, TOKEN_EXCHANGE_USER_PAYLOAD, cosmosDbMethod, null, cosmosFailureException);

        verify(mDataStoreEventListener).onDataStoreOperationResult(
                eq(operation),
                isNull(DocumentMetadata.class),
                documentErrorArgumentCaptor.capture());
        DataException documentError = documentErrorArgumentCaptor.getValue();
        assertNotNull(documentError);
        verifyNoMoreInteractions(mDataStoreEventListener);
        assertEquals(cosmosFailureException, documentError.getCause());
        verify(mLocalDocumentStorage, never()).deleteOnline(anyString(), anyString(), anyString());
    }

    @Test
    public void unsupportedPendingOperation() {
        when(mLocalDocumentStorage.getPendingOperations(USER_TABLE_NAME)).thenReturn(
                new ArrayList<PendingOperation>() {{
                    add(new PendingOperation(
                            USER_TABLE_NAME,
                            "Order a coffee",
                            RESOLVED_USER_PARTITION,
                            DOCUMENT_ID,
                            "document",
                            FUTURE_TIMESTAMP,
                            CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP));
                }});
        mData.onNetworkStateUpdated(true);
        verifyZeroInteractions(mHttpClient);
        verifyZeroInteractions(mDataStoreEventListener);
    }

    @Test
    public void networkGoesOfflineDoesNothing() {
        mData.onNetworkStateUpdated(false);
        verifyZeroInteractions(mHttpClient);
        verifyZeroInteractions(mLocalDocumentStorage);
        verifyZeroInteractions(mDataStoreEventListener);
    }

    @Test
    public void tokenExchangeCallFailsOnDelete() throws JSONException {
        verifyTokenExchangeCallFails(PENDING_OPERATION_DELETE_VALUE);
    }

    @Test
    public void tokenExchangeCallFailsOnCreateOrUpdate() throws JSONException {
        verifyTokenExchangeCallFails(PENDING_OPERATION_CREATE_VALUE);
    }

    private void verifyTokenExchangeCallFails(String operation) throws JSONException {
        final PendingOperation pendingOperation = new PendingOperation(
                USER_TABLE_NAME,
                operation,
                RESOLVED_USER_PARTITION,
                DOCUMENT_ID,
                "document",
                FUTURE_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP);
        when(mLocalDocumentStorage.getPendingOperations(USER_TABLE_NAME)).thenReturn(
                new ArrayList<PendingOperation>() {{
                    add(pendingOperation);
                }});

        Data.setDataStoreRemoteOperationListener(mDataStoreEventListener);
        mData.onNetworkStateUpdated(true);
        verifyTokenExchangeFlow(null, new Exception("Yeah, it failed."));

        ArgumentCaptor<DataException> documentErrorArgumentCaptor = ArgumentCaptor.forClass(DataException.class);
        verify(mDataStoreEventListener).onDataStoreOperationResult(
                eq(operation),
                isNull(DocumentMetadata.class),
                documentErrorArgumentCaptor.capture());
        DataException documentError = documentErrorArgumentCaptor.getValue();
        assertNotNull(documentError);
        verifyNoMoreInteractions(mDataStoreEventListener);
    }

    @Test
    public void pendingDeleteOperationSuccess() throws JSONException {
        final PendingOperation pendingOperation = new PendingOperation(
                USER_TABLE_NAME,
                PENDING_OPERATION_DELETE_VALUE,
                RESOLVED_USER_PARTITION,
                DOCUMENT_ID,
                "document",
                FUTURE_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP);
        when(mLocalDocumentStorage.getPendingOperations(USER_TABLE_NAME)).thenReturn(
                new ArrayList<PendingOperation>() {{
                    add(pendingOperation);
                }});
        ArgumentCaptor<DocumentMetadata> documentMetadataArgumentCaptor = ArgumentCaptor.forClass(DocumentMetadata.class);

        Data.setDataStoreRemoteOperationListener(mDataStoreEventListener);
        mData.onNetworkStateUpdated(true);

        verifyTokenExchangeToCosmosDbFlow(DOCUMENT_ID, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_DELETE, "", null);

        verify(mDataStoreEventListener).onDataStoreOperationResult(
                eq(PENDING_OPERATION_DELETE_VALUE),
                documentMetadataArgumentCaptor.capture(),
                isNull(DataException.class));
        DocumentMetadata documentMetadata = documentMetadataArgumentCaptor.getValue();
        assertNotNull(documentMetadata);
        verifyNoMoreInteractions(mDataStoreEventListener);

        assertEquals(DOCUMENT_ID, documentMetadata.getDocumentId());
        assertEquals(RESOLVED_USER_PARTITION, documentMetadata.getPartition());
        assertNull(documentMetadata.getETag());

        verify(mLocalDocumentStorage).deleteOnline(eq(pendingOperation.getTable()), eq(pendingOperation.getPartition()), eq(pendingOperation.getDocumentId()));
    }

    @Test
    public void pendingDeleteOperationWithCosmosDb404Error() throws JSONException {
        verifyPendingDeleteOperationWithCosmosDbError(404, false);
    }

    @Test
    public void pendingDeleteOperationWithCosmosDb409Error() throws JSONException {
        verifyPendingDeleteOperationWithCosmosDbError(409, false);
    }

    @Test
    public void pendingDeleteOperationDeletesExpiredOperationOnCosmosDb500Error() throws JSONException {
        verifyPendingDeleteOperationWithCosmosDbError(500, true);
    }

    private void verifyPendingDeleteOperationWithCosmosDbError(int httpStatusCode, boolean operationExpired) throws JSONException {
        long expirationTime = operationExpired ? PAST_TIMESTAMP : FUTURE_TIMESTAMP;
        final PendingOperation pendingOperation = new PendingOperation(
                USER_TABLE_NAME,
                PENDING_OPERATION_DELETE_VALUE,
                RESOLVED_USER_PARTITION,
                DOCUMENT_ID,
                "document",
                expirationTime,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP);
        when(mLocalDocumentStorage.getPendingOperations(USER_TABLE_NAME)).thenReturn(
                new ArrayList<PendingOperation>() {{
                    add(pendingOperation);
                }});
        ArgumentCaptor<DataException> documentErrorArgumentCaptor = ArgumentCaptor.forClass(DataException.class);

        Data.setDataStoreRemoteOperationListener(mDataStoreEventListener);
        mData.onNetworkStateUpdated(true);

        HttpException cosmosFailureException = new HttpException(httpStatusCode, "cosmos error");
        verifyTokenExchangeToCosmosDbFlow(DOCUMENT_ID, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_DELETE, null, cosmosFailureException);

        verify(mDataStoreEventListener).onDataStoreOperationResult(
                eq(pendingOperation.getOperation()),
                isNull(DocumentMetadata.class),
                documentErrorArgumentCaptor.capture());
        DataException documentError = documentErrorArgumentCaptor.getValue();
        assertNotNull(documentError);
        verifyNoMoreInteractions(mDataStoreEventListener);

        assertEquals(cosmosFailureException, documentError.getCause());

        verify(mLocalDocumentStorage).deleteOnline(eq(pendingOperation.getTable()), eq(pendingOperation.getPartition()), eq(pendingOperation.getDocumentId()));
    }

    @Test
    public void pendingDeleteOperationWithCosmosDb409ErrorNoListener() throws JSONException {
        final PendingOperation pendingOperation = new PendingOperation(
                USER_TABLE_NAME,
                PENDING_OPERATION_DELETE_VALUE,
                RESOLVED_USER_PARTITION,
                DOCUMENT_ID,
                "document",
                FUTURE_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP);
        when(mLocalDocumentStorage.getPendingOperations(USER_TABLE_NAME)).thenReturn(
                new ArrayList<PendingOperation>() {{
                    add(pendingOperation);
                }});

        mData.onNetworkStateUpdated(true);

        HttpException cosmosFailureException = new HttpException(409, "Conflict");
        verifyTokenExchangeToCosmosDbFlow(DOCUMENT_ID, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_DELETE, null, cosmosFailureException);
        verify(mLocalDocumentStorage).deleteOnline(eq(pendingOperation.getTable()), eq(pendingOperation.getPartition()), eq(pendingOperation.getDocumentId()));
    }
}
