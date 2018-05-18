/*
 * Copyright 2018 Emmanouil Gkatziouras
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gkatzioura.maven.cloud.abs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gkatzioura.maven.cloud.transfer.TransferProgress;
import com.gkatzioura.maven.cloud.transfer.TransferProgressFileInputStream;
import com.gkatzioura.maven.cloud.transfer.TransferProgressFileOutputStream;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;

public class AzureStorageRepository {

    private final String storageAccount;
    private final String container;

    private final ConnectionStringFactory connectionStringFactory;

    private CloudBlobContainer blobContainer;

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureStorageRepository.class);

    public AzureStorageRepository(String storageAccount, String directory) {
        this.connectionStringFactory = new ConnectionStringFactory();
        this.storageAccount = storageAccount;
        this.container = directory;
    }

    public void connect(AuthenticationInfo authenticationInfo) throws AuthenticationException {

        String connectionString = connectionStringFactory.create(authenticationInfo);
        try {
            CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(connectionString);
            blobContainer = cloudStorageAccount.createCloudBlobClient().getContainerReference(container);
            blobContainer.getMetadata();
        } catch (URISyntaxException |InvalidKeyException |StorageException e) {
            throw new AuthenticationException("Provide valid credentials");
        }
    }

    public void copy(String resourceName, File destination, TransferProgress transferProgress) throws ResourceDoesNotExistException {

        LOGGER.debug("Downloading key {} from container {} into {}",resourceName,container,destination.getAbsolutePath());

        try {

            CloudBlob cloudBlob = blobContainer.getBlobReferenceFromServer(resourceName);

            if(!cloudBlob.exists()) {
                LOGGER.debug("Blob {} does not exist",resourceName);
                throw new ResourceDoesNotExistException(resourceName);
            }

            try(OutputStream outputStream = new TransferProgressFileOutputStream(destination, transferProgress);
                InputStream inputStream = cloudBlob.openInputStream()) {
                IOUtils.copy(inputStream,outputStream);
            }
        } catch (URISyntaxException |StorageException |IOException e) {
            throw new ResourceDoesNotExistException("Could not download file from repo",e);
        }
    }

    public boolean newResourceAvailable(String resourceName,long timeStamp) throws ResourceDoesNotExistException{

        LOGGER.debug("Checking if new key {} exists",resourceName);

        try {
            CloudBlob cloudBlob = blobContainer.getBlobReferenceFromServer(resourceName);
            if(!cloudBlob.exists()) {
                return false;
            }

            long updated = cloudBlob.getProperties().getLastModified().getTime();
            return updated>timeStamp;
        } catch (URISyntaxException |StorageException e) {
            LOGGER.error("Could not fetch cloud blob",e);
            throw new ResourceDoesNotExistException(resourceName);
        }
    }

    public void put(File file, String destination,TransferProgress transferProgress) throws TransferFailedException {

        LOGGER.debug("Uploading key {} ",destination);

        try {

            CloudBlockBlob blob = blobContainer.getBlockBlobReference(destination);

            try(InputStream inputStream = new TransferProgressFileInputStream(file,transferProgress)) {
                blob.upload(inputStream,-1);
            }
        } catch (URISyntaxException |StorageException | IOException e) {
            LOGGER.error("Could not fetch cloud blob",e);
            throw new TransferFailedException(destination);
        }
    }

    public boolean exists(String resourceName) throws TransferFailedException {

        try {
            CloudBlockBlob blob = blobContainer.getBlockBlobReference(resourceName);
            return blob.exists();
        } catch (URISyntaxException |StorageException e) {
            LOGGER.error("Could not fetch cloud blob",e);
            throw new TransferFailedException(resourceName);
        }
    }

    public List<String> list(String path) {

        LOGGER.info("Listing files for {}",path);

        List<String> blobs = new ArrayList<>();

        Iterable<ListBlobItem> blobItems = blobContainer.listBlobs();
        Iterator<ListBlobItem> iterator = blobItems.iterator();

        while (iterator.hasNext()) {

            ListBlobItem blobItem = iterator.next();

            if(blobItem instanceof CloudBlob) {

                CloudBlob cloudBlob = (CloudBlob) blobItem;
                blobs.add(cloudBlob.getName());
            }
        }

        return blobs;
    }

    public void disconnect() {
        blobContainer = null;
    }

}
