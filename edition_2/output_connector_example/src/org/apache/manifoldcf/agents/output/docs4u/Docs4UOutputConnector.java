/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.manifoldcf.agents.output.docs4u;

// These are the basic interfaces we'll need from ManifoldCF
import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.agents.interfaces.*;

// Utility includes
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

// This is where we get agent system loggers
import org.apache.manifoldcf.agents.system.Logging;

// This class implements system-wide static methods
import org.apache.manifoldcf.agents.system.ManifoldCF;

// This is the base output connector class.
import org.apache.manifoldcf.agents.output.BaseOutputConnector;

// Here's the UI helper classes.
import org.apache.manifoldcf.ui.util.Encoder;

// Here are the imports that are specific for this connector
import org.apache.manifoldcf.examples.docs4u.Docs4UAPI;
import org.apache.manifoldcf.examples.docs4u.D4UFactory;
import org.apache.manifoldcf.examples.docs4u.D4UDocInfo;
import org.apache.manifoldcf.examples.docs4u.D4UDocumentIterator;
import org.apache.manifoldcf.examples.docs4u.D4UException;

/** This is the Docs4U output connector class.  This extends the base output connectors class,
* which implements IOutputConnector, and provides some degree of insulation from future
* changes to the IOutputConnector interface.  It also provides us with basic support for
* the connector lifecycle methods, so we don't have to implement those each time.
* Note well: Output connectors should have no dependencies on classes from the 
* org.apache.manifoldcf.crawler or org.apache.manifoldcf.authorities packages.  They should only
* have dependencies on the agents and core packages.
*/
public class Docs4UOutputConnector extends BaseOutputConnector
{
  // These are the configuration parameter names
  
  /** Repository root parameter */
  protected final static String PARAMETER_REPOSITORY_ROOT = "rootdirectory";
  
  // These are the output specification node names
  
  /** Mapping regular expressions for access tokens to Docs4U user/group ID's */
  protected final static String NODE_SECURITY_MAP = "securitymap";
  /** Mapping from source metadata name to target metadata name */
  protected final static String NODE_METADATA_MAP = "metadatamap";
  /** The URL metadata name */
  protected final static String NODE_URL_METADATA_NAME = "urlmetadataname";
  
  // These are attribute names, which are shared among the nodes
  
  /** A value */
  protected final static String ATTRIBUTE_VALUE = "value";
  /** Source metadata name */
  protected final static String ATTRIBUTE_SOURCE = "source";
  /** Target metadata name */
  protected final static String ATTRIBUTE_TARGET = "target";
  
  // These are the activity names
  
  /** Save activity */
  protected final static String ACTIVITY_SAVE = "save";
  /** Delete activity */
  protected final static String ACTIVITY_DELETE = "delete";
  
  // Local constants
  
  /** Session expiration time interval */
  protected final static long SESSION_EXPIRATION_MILLISECONDS = 300000L;
  /** Database user lookup cache lifetime */
  protected final static long CACHE_LIFETIME = 300000L;
  
  // Local variables.
  
  /** The root directory */
  protected String rootDirectory = null;
  
  /** The Docs4U API session */
  protected Docs4UAPI session = null;
  /** The expiration time of the Docs4U API session */
  protected long sessionExpiration = -1L;
  
  /** The UserGroupLookupManager class */
  protected UserGroupLookupManager userGroupLookupManager = null;
  /** The lock manager */
  protected ILockManager lockManager = null;
  
  /** Constructor */
  public Docs4UOutputConnector()
  {
    super();
  }

  /** Return the list of activities that this output connector supports (i.e. writes into the log).
  * The connector does not have to be connected for this method to be called.
  *@return the list.
  */
  @Override
  public String[] getActivitiesList()
  {
    return new String[]{ACTIVITY_SAVE,ACTIVITY_DELETE};
  }

  /** Install the connector.
  * This method is called to initialize persistent storage for the connector, such as database tables etc.
  * It is called when the connector is registered.
  *@param threadContext is the current thread context.
  */
  @Override
  public void install(IThreadContext threadContext)
    throws ManifoldCFException
  {
    super.install(threadContext);
    new UserGroupLookupManager(threadContext).initialize();
  }

  /** Uninstall the connector.
  * This method is called to remove persistent storage for the connector, such as database tables etc.
  * It is called when the connector is deregistered.
  *@param threadContext is the current thread context.
  */
  @Override
  public void deinstall(IThreadContext threadContext)
    throws ManifoldCFException
  {
    new UserGroupLookupManager(threadContext).destroy();
    super.deinstall(threadContext);
  }

  /** Clear out any state information specific to a given thread.
  * This method is called when this object is returned to the connection pool.
  */
  @Override
  public void clearThreadContext()
  {
    userGroupLookupManager = null;
    lockManager = null;
    super.clearThreadContext();
  }

  /** Attach to a new thread.
  *@param threadContext is the new thread context.
  */
  @Override
  public void setThreadContext(IThreadContext threadContext)
    throws ManifoldCFException
  {
    super.setThreadContext(threadContext);
    userGroupLookupManager = new UserGroupLookupManager(threadContext);
    lockManager = LockManagerFactory.make(threadContext);
  }

  /** Output the configuration header section.
  * This method is called in the head section of the connector's configuration page.  Its purpose is to
  * add the required tabs to the list, and to output any javascript methods that might be needed by
  * the configuration editing HTML.
  * The connector does not need to be connected for this method to be called.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale for the header.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, List<String> tabsArray)
    throws ManifoldCFException, IOException
  {
    tabsArray.add("Repository");
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    Messages.outputResourceWithVelocity(out, locale, "ConfigurationHeader.html", velocityContext);
  }

  /** Output the configuration body section.
  * This method is called in the body section of the connector's configuration page.  Its purpose is to
  * present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within
  * appropriate <html>, <body>, and <form> tags.  The name of the form is always "editconnection".
  * The connector does not need to be connected for this method to be called.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale of the output.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabName is the current tab name.
  */
  @Override
  public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, String tabName)
    throws ManifoldCFException, IOException
  {
    // Output Repository tab
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    velocityContext.put("TabName", tabName);
    fillInRepositoryParameters(velocityContext, parameters);
    Messages.outputResourceWithVelocity(out, locale, "Configuration_Repository.html", velocityContext);
  }

  /** Process a configuration post.
  * This method is called at the start of the connector's configuration page, whenever there is a possibility
  * that form data for a connection has been posted.  Its purpose is to gather form information and modify
  * the configuration parameters accordingly.
  * The name of the posted form is always "editconnection".
  * The connector does not need to be connected for this method to be called.
  *@param threadContext is the local thread context.
  *@param variableContext is the set of variables available from the post, including binary file post information.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of the
  *   connection (and cause a redirection to an error page).
  */
  @Override
  public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext,
    ConfigParams parameters)
    throws ManifoldCFException
  {
    String repositoryRoot = variableContext.getParameter("repositoryroot");
    if (repositoryRoot != null)
      parameters.setParameter(PARAMETER_REPOSITORY_ROOT,repositoryRoot);
    return null;
  }

  /** View configuration.
  * This method is called in the body section of the connector's view configuration page.  Its purpose is to present
  * the connection information to the user.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html> and <body> tags.
  * The connector does not need to be connected for this method to be called.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale of the output.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  */
  @Override
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out, Locale locale,
    ConfigParams parameters)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    
    // Fill in all the tabs at once
    
    // Repository tab
    fillInRepositoryParameters(velocityContext, parameters);
    
    // Output
    Messages.outputResourceWithVelocity(out, locale, "ConfigurationView.html", velocityContext);
  }
  
  /** Get the current session, or create one if not valid.
  */
  protected Docs4UAPI getSession()
    throws ManifoldCFException, ServiceInterruption
  {
    if (session == null)
    {
      // We need to establish a new session
      try
      {
        session = D4UFactory.makeAPI(rootDirectory);
      }
      catch (D4UException e)
      {
        // Here we need to decide if the exception is transient or permanent.
        // Permanent exceptions should throw ManifoldCFException.  Transient
        // ones should throw an appropriate ServiceInterruption, based on the
        // actual error.
        Logging.ingest.warn("Docs4U: Session setup error: "+e.getMessage(),e);
        throw new ManifoldCFException("Session setup error: "+e.getMessage(),e);
      }
    }
    // Reset the expiration time
    sessionExpiration = System.currentTimeMillis() + SESSION_EXPIRATION_MILLISECONDS;
    return session;
  }
  
  /** Expire any current session.
  */
  protected void expireSession()
  {
    session = null;
    sessionExpiration = -1L;
  }
  
  /** Connect.
  *@param configParameters is the set of configuration parameters, which
  * in this case describe the root directory.
  */
  @Override
  public void connect(ConfigParams configParameters)
  {
    super.connect(configParameters);
    // This is needed by getDocumentBins()
    rootDirectory = configParameters.getParameter(PARAMETER_REPOSITORY_ROOT);
  }

  /** Close the connection.  Call this before discarding this instance of the
  * connector.
  */
  @Override
  public void disconnect()
    throws ManifoldCFException
  {
    expireSession();
    rootDirectory = null;
    super.disconnect();
  }

  /** Test the connection.  Returns a string describing the connection integrity.
  *@return the connection's status as a displayable string.
  */
  @Override
  public String check()
    throws ManifoldCFException
  {
    try
    {
      // Get or establish the session
      Docs4UAPI currentSession = getSession();
      // Check session integrity
      try
      {
        currentSession.sanityCheck();
      }
      catch (D4UException e)
      {
        Logging.ingest.warn("Docs4U: Error checking repository: "+e.getMessage(),e);
        return "Error: "+e.getMessage();
      }
      // If it passed, return "everything ok" message
      return super.check();
    }
    catch (ServiceInterruption e)
    {
      // Convert service interruption into a transient error for display
      return "Transient error: "+e.getMessage();
    }
  }

  /** This method is periodically called for all connectors that are connected but not
  * in active use.
  */
  @Override
  public void poll()
    throws ManifoldCFException
  {
    if (session != null)
    {
      if (System.currentTimeMillis() >= sessionExpiration)
        expireSession();
    }
  }

  /** Request arbitrary connector information.
  * This method is called directly from the API in order to allow API users to perform any one of several
  * connector-specific queries.  These are usually used to create external UI's.  The connector will be
  * connected before this method is called.
  *@param output is the response object, to be filled in by this method.
  *@param command is the command, which is taken directly from the API request.
  *@return true if the resource is found, false if not.  In either case, output may be filled in.
  */
  @Override
  public boolean requestInfo(Configuration output, String command)
    throws ManifoldCFException
  {
    // Look for the commands we know about
    if (command.equals("metadata"))
    {
      // Use a try/catch to capture errors from repository communication
      try
      {
        // Get the metadata names
        String[] metadataNames = getMetadataNames();
        // Code these up in the output, in a form that yields decent JSON
        int i = 0;
        while (i < metadataNames.length)
        {
          String metadataName = metadataNames[i++];
          // Construct an appropriate node
          ConfigurationNode node = new ConfigurationNode("metadata");
          ConfigurationNode child = new ConfigurationNode("name");
          child.setValue(metadataName);
          node.addChild(node.getChildCount(),child);
          output.addChild(output.getChildCount(),node);
        }
      }
      catch (ServiceInterruption e)
      {
        ManifoldCF.createServiceInterruptionNode(output,e);
      }
      catch (ManifoldCFException e)
      {
        ManifoldCF.createErrorNode(output,e);
      }
    }
    else
      return super.requestInfo(output,command);
    return true;
  }

  /** Detect if a mime type is indexable or not.  This method is used by participating repository connectors to pre-filter the number of
  * unusable documents that will be passed to this output connector.
  *@param mimeType is the mime type of the document.
  *@return true if the mime type is indexable by this connector.
  */
  @Override
  public boolean checkMimeTypeIndexable(String mimeType)
    throws ManifoldCFException, ServiceInterruption
  {
    return true;
  }

  /** Pre-determine whether a document (passed here as a File object) is indexable by this connector.  This method is used by participating
  * repository connectors to help reduce the number of unmanageable documents that are passed to this output connector in advance of an
  * actual transfer.  This hook is provided mainly to support search engines that only handle a small set of accepted file types.
  *@param localFile is the local file to check.
  *@return true if the file is indexable.
  */
  @Override
  public boolean checkDocumentIndexable(File localFile)
    throws ManifoldCFException, ServiceInterruption
  {
    return true;
  }

  /** Get an output version string, given an output specification.  The output version string is used to uniquely describe the pertinent details of
  * the output specification and the configuration, to allow the Connector Framework to determine whether a document will need to be output again.
  * Note that the contents of the document cannot be considered by this method, and that a different version string (defined in IRepositoryConnector)
  * is used to describe the version of the actual document.
  *
  * This method presumes that the connector object has been configured, and it is thus able to communicate with the output data store should that be
  * necessary.
  *@param spec is the current output specification for the job that is doing the crawling.
  *@return a string, of unlimited length, which uniquely describes output configuration and specification in such a way that if two such strings are equal,
  * the document will not need to be sent again to the output data store.
  */
  @Override
  public String getOutputDescription(OutputSpecification spec)
    throws ManifoldCFException, ServiceInterruption
  {
    String urlMetadataName = "";
    String securityMap = "";
    List<String> metadataMappings = new ArrayList<String>();
    
    int i = 0;
    while (i < spec.getChildCount())
    {
      SpecificationNode sn = spec.getChild(i++);
      if (sn.getType().equals(NODE_URL_METADATA_NAME))
        urlMetadataName = sn.getAttributeValue(ATTRIBUTE_VALUE);
      else if (sn.getType().equals(NODE_SECURITY_MAP))
        securityMap = sn.getAttributeValue(ATTRIBUTE_VALUE);
      else if (sn.getType().equals(NODE_METADATA_MAP))
      {
        String recordSource = sn.getAttributeValue(ATTRIBUTE_SOURCE);
        String recordTarget = sn.getAttributeValue(ATTRIBUTE_TARGET);
        String[] fixedList = new String[]{recordSource,recordTarget};
        StringBuilder packBuffer = new StringBuilder();
        packFixedList(packBuffer,fixedList,':');
        metadataMappings.add(packBuffer.toString());
      }
    }
    
    // Now, form the final string.
    StringBuilder sb = new StringBuilder();
    
    pack(sb,urlMetadataName,'+');
    pack(sb,securityMap,'+');
    packList(sb,metadataMappings,',');

    return sb.toString();
  }

  /** Add (or replace) a document in the output data store using the connector.
  * This method presumes that the connector object has been configured, and it is thus able to communicate with the output data store should that be
  * necessary.
  * The OutputSpecification is *not* provided to this method, because the goal is consistency, and if output is done it must be consistent with the
  * output description, since that was what was partly used to determine if output should be taking place.  So it may be necessary for this method to decode
  * an output description string in order to determine what should be done.
  *@param documentURI is the URI of the document.  The URI is presumed to be the unique identifier which the output data store will use to process
  * and serve the document.  This URI is constructed by the repository connector which fetches the document, and is thus universal across all output connectors.
  *@param outputDescription is the description string that was constructed for this document by the getOutputDescription() method.
  *@param document is the document data to be processed (handed to the output data store).
  *@param authorityNameString is the name of the authority responsible for authorizing any access tokens passed in with the repository document.  May be null.
  *@param activities is the handle to an object that the implementer of an output connector may use to perform operations, such as logging processing activity.
  *@return the document status (accepted or permanently rejected).
  */
  @Override
  public int addOrReplaceDocument(String documentURI, String outputDescription,
    RepositoryDocument document, String authorityNameString, IOutputAddActivity activities)
    throws ManifoldCFException, ServiceInterruption
  {
    // First, unpack the output description.
    int index = 0;
    StringBuilder urlMetadataNameBuffer = new StringBuilder();
    StringBuilder securityMapBuffer = new StringBuilder();
    List<String> metadataMappings = new ArrayList<String>();

    index = unpack(urlMetadataNameBuffer,outputDescription,index,'+');
    index = unpack(securityMapBuffer,outputDescription,index,'+');
    index = unpackList(metadataMappings,outputDescription,index,',');
    
    String urlMetadataName = urlMetadataNameBuffer.toString();
    Map<String,String> fieldMap = new HashMap<String,String>();
    int j = 0;
    while (j < metadataMappings.size())
    {
      String metadataMapping = metadataMappings.get(j++);
      // Unpack
      String[] mappingData = new String[2];
      unpackFixedList(mappingData,metadataMapping,0,':');
      fieldMap.put(mappingData[0],mappingData[1]);
    }

    MatchMap securityMap = new MatchMap(securityMapBuffer.toString());
    
    // Handle activity logging.
    long startTime = System.currentTimeMillis();
    String resultCode = "OK";
    String resultReason = null;
    long byteCount = 0L;
    
    // Expire stuff before we get going.
    userGroupLookupManager.cleanupExpiredRecords(startTime);
    
    try
    {
      // Get a Docs4U session to work with.
      Docs4UAPI session = getSession();
      try
      {
        // Let's form the D4UDocInfo object for the document.  Do this first, since there's no
        // guarantee we'll succeed here.
        
        D4UDocInfo docObject = D4UFactory.makeDocInfo();
        try
        {
          // First, fill in the security info, since that might well cause us to reject the document outright.
          // We can only accept the document if the security information is compatible with the Docs4U
          // model, and if the mapped user or group exists in the target repository.
          
          if (document.countDirectoryACLs() > 0)
          {
            resultCode = "REJECTED";
            resultReason = "Directory ACLs present";
            return DOCUMENTSTATUS_REJECTED;
          }
          
          String[] shareAcl = document.getShareACL();
          String[] shareDenyAcl = document.getShareDenyACL();
          if ((shareAcl != null && shareAcl.length > 0) ||
            (shareDenyAcl != null && shareDenyAcl.length > 0))
          {
            resultCode = "REJECTED";
            resultReason = "Share ACLs present";
            return DOCUMENTSTATUS_REJECTED;
          }
          
          String[] acl = performUserGroupMapping(document.getACL(),securityMap,startTime);
          String[] denyAcl = performUserGroupMapping(document.getDenyACL(),securityMap,startTime);
          if (acl == null || denyAcl == null)
          {
            resultCode = "REJECTED";
            resultReason = "Access tokens did not map";
            return DOCUMENTSTATUS_REJECTED;
          }
          docObject.setAllowed(acl);
          docObject.setDisallowed(denyAcl);
          
          // Next, map the metadata.  If this doesn't succeed, nothing is lost and we can still continue.
          docObject.setMetadata(urlMetadataName,new String[]{documentURI});
          Iterator<String> fields = document.getFields();
          while (fields.hasNext())
          {
            String field = fields.next();
            String mappedField = fieldMap.get(field);
            if (mappedField != null)
            {
              if (Logging.ingest.isDebugEnabled())
                Logging.ingest.debug("For document '"+documentURI+"', field '"+field+"' maps to target field '"+mappedField+"'");
              // We have a source field and a target field; copy the attribute
              String[] stringValues = document.getFieldAsStrings(field);
              docObject.setMetadata(mappedField,stringValues);
            }
            else
            {
              if (Logging.ingest.isDebugEnabled())
                Logging.ingest.debug("For document '"+documentURI+"', field '"+field+"' discarded");
            }
          }
          
          // Finally, copy the content.  The input stream returned by getBinaryStream() should NOT
          // be closed, just read.
          byteCount = document.getBinaryLength();
          docObject.setData(document.getBinaryStream());
          
          // Next, look up the Docs4U identifier for the document.
          Map<String,String> lookupMap = new HashMap<String,String>();
          lookupMap.put(urlMetadataName,documentURI);
          D4UDocumentIterator iter = session.findDocuments(null,null,lookupMap);
          String documentID;
          if (iter.hasNext())
          {
            documentID = iter.getNext();
            session.updateDocument(documentID,docObject);
          }
          else
            documentID = session.createDocument(docObject);
          return DOCUMENTSTATUS_ACCEPTED;
        }
        catch (IOException e)
        {
          throw new ManifoldCFException(e.getMessage(),e);
        }
        finally
        {
          docObject.close();
        }
      }
      catch (InterruptedException e)
      {
        // Throw an interruption signal.
        throw new ManifoldCFException(e.getMessage(),e,ManifoldCFException.INTERRUPTED);
      }
      catch (D4UException e)
      {
        Logging.ingest.warn("Docs4U: Error ingesting '"+documentURI+"': "+e.getMessage(),e);
        // Decide whether this is a service interruption or a real error, and throw accordingly.
        // Docs4U never throws service interruptions.
        throw new ManifoldCFException("Error ingesting '"+documentURI+"': "+e.getMessage(),e);
      }
    }
    catch (ManifoldCFException e)
    {
      if (e.getErrorCode() == ManifoldCFException.INTERRUPTED)
      {
        resultCode = null;
        throw e;
      }
      resultCode = "ERROR";
      resultReason = e.getMessage();
      throw e;
    }
    catch (ServiceInterruption e)
    {
      resultCode = "ERROR";
      resultReason = e.getMessage();
      throw e;
    }
    finally
    {
      // Log the activity - but only if it wasn't interrupted
      if (resultCode != null)
      {
        activities.recordActivity(new Long(startTime),ACTIVITY_SAVE,new Long(byteCount),documentURI,
          resultCode,resultReason);
      }
    }
  }

  /** Fill in the Velocity context for the Repository configuration tab.
  *@param velocityContext is the context to be filled in.
  *@param parameters are the current connector parameters.
  */
  protected static void fillInRepositoryParameters(Map<String,Object> velocityContext,
    ConfigParams parameters)
  {
    String repositoryRoot = parameters.getParameter(PARAMETER_REPOSITORY_ROOT);
    if (repositoryRoot == null)
      repositoryRoot = "";
    velocityContext.put(PARAMETER_REPOSITORY_ROOT, repositoryRoot);
  }

  /** Perform the mapping from access token to user/group name to Docs4U user/group ID.
  *@param inputACL is the access tokens to map.
  *@param securityMap is the mapping object.
  *@param currentTime is the current time in milliseconds since epoch.
  *@return null if the mapping cannot be performed, in which case the document will be
  * rejected by the caller, or the mapped user/group ID's.
  */
  protected String[] performUserGroupMapping(String[] inputACL, MatchMap securityMap,
    long currentTime)
    throws ManifoldCFException, ServiceInterruption
  {
    if (inputACL == null)
      return new String[0];
    // Create an output list
    String[] rval = new String[inputACL.length];
    int i = 0;
    while (i < rval.length)
    {
      String inputToken = inputACL[i];
      String mappedUserGroup = securityMap.translate(inputToken);
      String userGroupID = lookupUserGroup(mappedUserGroup,currentTime);
      if (userGroupID == null)
        return null;
      rval[i++] = userGroupID;
    }
    return rval;
  }
  
  /** Lookup the user/group id given the user/group name.
  * This is a slow operation for Docs4U, so I've built a database table where we can look it up quickly, if it exists.
  *@param userGroupName is the name of the user/group.
  *@param currentTime is the current time in milliseconds since epoch.
  *@return the user/group ID, or null if not found.
  */
  protected String lookupUserGroup(String userGroupName, long currentTime)
    throws ManifoldCFException, ServiceInterruption
  {
    // We need to put a lock around things to prevent race conditions.
    String lockName = makeLockName(userGroupName);
    lockManager.enterWriteLock(lockName);
    try
    {
      // Try the database first
      String userGroupID = userGroupLookupManager.lookupUserGroup(rootDirectory,userGroupName);
      if (userGroupID != null)
        return userGroupID;
      // Ok, we have to look it up in Docs4U.
      try
      {
        Docs4UAPI session = getSession();
        userGroupID = session.findUserOrGroup(userGroupName);
        if (userGroupID == null)
          return null;
        // Save it in database for future reference
        userGroupLookupManager.addUserGroup(rootDirectory,userGroupName,userGroupID,currentTime + CACHE_LIFETIME);
        return userGroupID;
      }
      catch (InterruptedException e)
      {
        throw new ManifoldCFException(e.getMessage(),e,ManifoldCFException.INTERRUPTED);
      }
      catch (D4UException e)
      {
        Logging.ingest.error("Error looking up user/group: "+e.getMessage(),e);
        throw new ManifoldCFException("Error looking up user/group: "+e.getMessage(),e);
      }
    }
    finally
    {
      lockManager.leaveWriteLock(lockName);
    }
  }
  
  /** Remove a document using the connector.
  * Note that the last outputDescription is included, since it may be necessary for the connector to use such information to know how to properly remove the document.
  *@param documentURI is the URI of the document.  The URI is presumed to be the unique identifier which the output data store will use to process
  * and serve the document.  This URI is constructed by the repository connector which fetches the document, and is thus universal across all output connectors.
  *@param outputDescription is the last description string that was constructed for this document by the getOutputDescription() method above.
  *@param activities is the handle to an object that the implementer of an output connector may use to perform operations, such as logging processing activity.
  */
  @Override
  public void removeDocument(String documentURI, String outputDescription, IOutputRemoveActivity activities)
    throws ManifoldCFException, ServiceInterruption
  {
    // Unpack what we need from the output description
    StringBuilder urlMetadataNameBuffer = new StringBuilder();
    unpack(urlMetadataNameBuffer,outputDescription,0,'+');
    String urlMetadataName = urlMetadataNameBuffer.toString();

    // Handle activity logging.
    long startTime = System.currentTimeMillis();
    String resultCode = "OK";
    String resultReason = null;
    
    try
    {
      // Get a Docs4U session to work with.
      Docs4UAPI session = getSession();
      try
      {
        Map<String,String> lookupMap = new HashMap<String,String>();
        lookupMap.put(urlMetadataName,documentURI);
        D4UDocumentIterator iter = session.findDocuments(null,null,lookupMap);
        if (iter.hasNext())
        {
          String documentID = iter.getNext();
          session.deleteDocument(documentID);
        }
      }
      catch (InterruptedException e)
      {
        // We don't log interruptions, just exit immediately.
        resultCode = null;
        // Throw an interruption signal.
        throw new ManifoldCFException(e.getMessage(),e,ManifoldCFException.INTERRUPTED);
      }
      catch (D4UException e)
      {
        resultCode = "ERROR";
        resultReason = e.getMessage();
        Logging.ingest.warn("Docs4U: Error removing '"+documentURI+"': "+e.getMessage(),e);
        // Decide whether this is a service interruption or a real error, and throw accordingly.
        throw new ManifoldCFException("Error removing '"+documentURI+"': "+e.getMessage(),e);
      }
    }
    finally
    {
      // Log the activity - but only if it wasn't interrupted
      if (resultCode != null)
      {
        activities.recordActivity(new Long(startTime),ACTIVITY_DELETE,null,documentURI,
          resultCode,resultReason);
      }
    }
  }

  /** Notify the connector of a completed job.
  * This is meant to allow the connector to flush any internal data structures it has been keeping around, or to tell the output repository that this
  * is a good time to synchronize things.  It is called whenever a job is either completed or aborted.
  *@param activities is the handle to an object that the implementer of an output connector may use to perform operations, such as logging processing activity.
  */
  @Override
  public void noteJobComplete(IOutputNotifyActivity activities)
    throws ManifoldCFException, ServiceInterruption
  {
    // Does nothing for Docs4U
  }

  // UI support methods.
  //
  // The UI support methods come in two varieties.  The first group (inherited from IConnector) is involved
  //  in setting up connection configuration information.
  //
  // The second group is listed here.  These methods are is involved in presenting and editing output specification
  //  information for a job.
  //
  // The two kinds of methods are accordingly treated differently, in that the first group cannot assume that
  // the current connector object is connected, while the second group can.  That is why the first group
  // receives a thread context argument for all UI methods, while the second group does not need one
  // (since it has already been applied via the connect() method).
    
  /** Output the specification header section.
  * This method is called in the head section of a job page which has selected an output connection of the
  * current type.  Its purpose is to add the required tabs to the list, and to output any javascript methods
  * that might be needed by the job editing HTML.
  * The connector will be connected before this method can be called.
  *@param out is the output to which any HTML should be sent.
  *@param os is the current output specification for this job.
  *@param locale is the desired locale.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputSpecificationHeader(IHTTPOutput out, Locale locale, OutputSpecification os,
    List<String> tabsArray)
    throws ManifoldCFException, IOException
  {
    // Add the tabs
    tabsArray.add("Docs4U Metadata");
    tabsArray.add("Docs4U Security");
    
    // Output the header
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    Messages.outputResourceWithVelocity(out, locale, "SpecificationHeader.html", velocityContext);
  }

  /** Output the specification body section.
  * This method is called in the body section of a job page which has selected an output connection of the
  * current type.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate
  *  <html>, <body>, and <form> tags.  The name of the form is always "editjob".
  * The connector will be connected before this method can be called.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale.
  *@param os is the current output specification for this job.
  *@param tabName is the current tab name.
  */
  @Override
  public void outputSpecificationBody(IHTTPOutput out, Locale locale, OutputSpecification os,
    String tabName)
    throws ManifoldCFException, IOException
  {
    // Do the "Metadata Mapping" tab
    outputMetadataMappingTab(out,locale,os,tabName);
    // Do the "Access Mapping" tab
    outputAccessMappingTab(out,locale,os,tabName);
  }
  
  /** Take care of "Metadata Mapping" tab.
  */
  protected void outputMetadataMappingTab(IHTTPOutput out, Locale locale, OutputSpecification os, String tabName)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    velocityContext.put("TabName",tabName);
    fillInMetadataMappingTab(velocityContext,os);
    fillInMetadataMappingTabSelection(velocityContext);
    Messages.outputResourceWithVelocity(out, locale, "Specification_Docs4U_Metadata.html", velocityContext);
  }
  
  /** Fill in data for Metadata display.
  */
  protected static void fillInMetadataMappingTab(
    Map<String,Object> velocityContext, OutputSpecification os)
  {
    // Scan the output specification, and convert to things Velocity understands
    String urlMetadataName = "";
    List<MappingRow> mappings = new ArrayList<MappingRow>();
    Set<String> usedAttributes = new HashSet<String>();
    int i = 0;
    while (i < os.getChildCount())
    {
      SpecificationNode sn = os.getChild(i++);
      if (sn.getType().equals(NODE_URL_METADATA_NAME))
        urlMetadataName = sn.getAttributeValue(ATTRIBUTE_VALUE);
      else if (sn.getType().equals(NODE_METADATA_MAP))
      {
        String metadataRecordSource = sn.getAttributeValue(ATTRIBUTE_SOURCE);
        String metadataRecordTarget = sn.getAttributeValue(ATTRIBUTE_TARGET);
        usedAttributes.add(metadataRecordTarget);
        mappings.add(new MappingRow(metadataRecordSource,metadataRecordTarget));
      }
    }
    velocityContext.put("urlmetadataname",urlMetadataName);
    velocityContext.put("metadatarecords",mappings);
    velocityContext.put("usedattributes",usedAttributes);
  }

  /** Fill in data for Metadata selection.
  */
  protected void fillInMetadataMappingTabSelection(
    Map<String,Object> velocityContext)
  {
    try
    {
      String[] matchNames = getMetadataNames();
      velocityContext.put("urlmetadataattributes",matchNames);
      velocityContext.put("error","");
    }
    catch (ManifoldCFException e)
    {
      velocityContext.put("error","Error: "+e.getMessage());
    }
    catch (ServiceInterruption e)
    {
      velocityContext.put("error","Transient error: "+e.getMessage());
    }

  }

  /** Take care of "Access Mapping" tab.
  */
  protected void outputAccessMappingTab(IHTTPOutput out, Locale locale, OutputSpecification os, String tabName)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    velocityContext.put("TabName",tabName);
    fillInAccessMappingTab(velocityContext,os);
    Messages.outputResourceWithVelocity(out, locale, "Specification_Docs4U_Security.html", velocityContext);
  }

  /** Fill in data for "Security" tab.
  */
  protected static void fillInAccessMappingTab(Map<String,Object> velocityContext,
    OutputSpecification os)
  {
    int i;
    
    // Look for mapping data
    MatchMap mm = new MatchMap();
    mm.appendMatchPair("(.*)","$(1)");
    
    i = 0;
    while (i < os.getChildCount())
    {
      SpecificationNode sn = os.getChild(i++);
      if (sn.getType().equals(NODE_SECURITY_MAP))
      {
        String mappingString = sn.getAttributeValue(ATTRIBUTE_VALUE);
        mm = new MatchMap(mappingString);
      }
    }
    String regexp = mm.getMatchString(0);
    String translation = mm.getReplaceString(0);
    velocityContext.put("regexp",regexp);
    velocityContext.put("translation",translation);
  }
  
  /** Process a specification post.
  * This method is called at the start of job's edit or view page, whenever there is a possibility that form
  * data for a connection has been posted.  Its purpose is to gather form information and modify the
  * output specification accordingly.  The name of the posted form is always "editjob".
  * The connector will be connected before this method can be called.
  *@param variableContext contains the post data, including binary file-upload information.
  *@param os is the current output specification for this job.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of
  * the job (and cause a redirection to an error page).
  */
  public String processSpecificationPost(IPostParameters variableContext, OutputSpecification os)
    throws ManifoldCFException
  {
    // Pick up the Metadata Mapping tab data
    String rval = processMetadataMappingTab(variableContext,os);
    if (rval != null)
      return rval;
    // Pick up the Access Mapping tab data
    rval = processAccessMappingTab(variableContext,os);
    return rval;
  }
  
  /** Process form post for metadata tab.
  */
  protected String processMetadataMappingTab(IPostParameters variableContext, OutputSpecification os)
    throws ManifoldCFException
  {
    // Remove old url metadata name
    removeNodes(os,NODE_URL_METADATA_NAME);
    // Get the url metadata name
    String urlMetadataName = variableContext.getParameter("ocurlmetadataname");
    if (urlMetadataName != null)
      addUrlMetadataNameNode(os,urlMetadataName);
    
    // Remove old metadata mapping output specification information
    removeNodes(os,NODE_METADATA_MAP);
    
    // Parse the number of records that were posted
    String recordCountString = variableContext.getParameter("ocmetadatacount");
    if (recordCountString != null)
    {
      int recordCount = Integer.parseInt(recordCountString);
              
      // Loop throught them and add to the new document specification information
      int i = 0;
      while (i < recordCount)
      {
        String suffix = "_"+Integer.toString(i++);
        // Only add the name/value if the item was not deleted.
        String metadataOp = variableContext.getParameter("ocmetadataop"+suffix);
        if (metadataOp == null || !metadataOp.equals("Delete"))
        {
          String metadataSource = variableContext.getParameter("ocmetadatasource"+suffix);
          String metadataTarget = variableContext.getParameter("ocmetadatatarget"+suffix);
          addMetadataMappingNode(os,metadataSource,metadataTarget);
        }
      }
    }
      
    // Now, look for a global "Add" operation
    String operation = variableContext.getParameter("ocmetadataop");
    if (operation != null && operation.equals("Add"))
    {
      // Pick up the global parameter name and value
      String metadataSource = variableContext.getParameter("ocmetadatasource");
      String metadataTarget = variableContext.getParameter("ocmetadatatarget");
      addMetadataMappingNode(os,metadataSource,metadataTarget);
    }

    return null;
  }

  /** Add a METADATA_MAP node to an output specification.
  */
  protected static void addMetadataMappingNode(OutputSpecification os,
    String metadataSource, String metadataTarget)
  {
    // Create a new specification node with the right characteristics
    SpecificationNode sn = new SpecificationNode(NODE_METADATA_MAP);
    sn.setAttribute(ATTRIBUTE_SOURCE,metadataSource);
    sn.setAttribute(ATTRIBUTE_TARGET,metadataTarget);
    // Add to the end
    os.addChild(os.getChildCount(),sn);
  }

  /** Add a URL_METADATA_NAME node to an output specification.
  */
  protected static void addUrlMetadataNameNode(OutputSpecification os,
    String urlMetadataName)
  {
    // Create a new specification node with the right characteristics
    SpecificationNode sn = new SpecificationNode(NODE_URL_METADATA_NAME);
    sn.setAttribute(ATTRIBUTE_VALUE,urlMetadataName);
    // Add to the end
    os.addChild(os.getChildCount(),sn);
  }
  
  /** Process form post for security tab.
  */
  protected String processAccessMappingTab(IPostParameters variableContext, OutputSpecification os)
    throws ManifoldCFException
  {
    // Remove old security map node
    removeNodes(os,NODE_SECURITY_MAP);

    String regexp = variableContext.getParameter("ocsecurityregexp");
    String translation = variableContext.getParameter("ocsecuritytranslation");
    if (regexp == null)
      regexp = "";
    if (translation == null)
      translation = "";
      
    MatchMap mm = new MatchMap();
    mm.appendMatchPair(regexp,translation);
      
    addSecurityMapNode(os,mm.toString());
    return null;
  }
  
  /** Add a SECURITY_MAP node to an output specification
  */
  protected static void addSecurityMapNode(OutputSpecification os, String value)
  {
    // Create a new specification node with the right characteristics
    SpecificationNode sn = new SpecificationNode(NODE_SECURITY_MAP);
    sn.setAttribute(ATTRIBUTE_VALUE,value);
    // Add to the end
    os.addChild(os.getChildCount(),sn);
  }
  
  /** Remove all of a specified node type from an output specification.
  */
  protected static void removeNodes(OutputSpecification os,
    String nodeTypeName)
  {
    int i = 0;
    while (i < os.getChildCount())
    {
      SpecificationNode sn = os.getChild(i);
      if (sn.getType().equals(nodeTypeName))
        os.removeChild(i);
      else
        i++;
    }
  }

  /** View specification.
  * This method is called in the body section of a job's view page.  Its purpose is to present the output
  * specification information to the user.  The coder can presume that the HTML that is output from
  * this configuration will be within appropriate <html> and <body> tags.
  * The connector will be connected before this method can be called.
  *@param out is the output to which any HTML should be sent.
  *@param os is the current output specification for this job.
  */
  @Override
  public void viewSpecification(IHTTPOutput out, Locale locale, OutputSpecification os)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    fillInMetadataMappingTab(velocityContext,os);
    fillInAccessMappingTab(velocityContext,os);
    Messages.outputResourceWithVelocity(out, locale, "SpecificationView.html", velocityContext);
  }

  /** Calculate a lock name given a user/group name.
  *@param userGroupName is the user/group name.
  *@return the lock name.
  */
  protected static String makeLockName(String userGroupName)
  {
    return "docs4u-usergroup-"+userGroupName;
  }
  
  // Protected UI support methods
  
  /** Get an ordered list of metadata names.
  */
  protected String[] getMetadataNames()
    throws ManifoldCFException, ServiceInterruption
  {
    Docs4UAPI currentSession = getSession();
    try
    {
      String[] rval = currentSession.getMetadataNames();
      java.util.Arrays.sort(rval);
      return rval;
    }
    catch (InterruptedException e)
    {
      throw new ManifoldCFException(e.getMessage(),e,ManifoldCFException.INTERRUPTED);
    }
    catch (D4UException e)
    {
      throw new ManifoldCFException(e.getMessage(),e);
    }
  }
  
  
  /** Nested class to represent a row in source/target mapping table.
  * This is used to communicate mappings to Velocity in a manner it can
  * make use of.
  */
  protected static class MappingRow
  {
    protected String source;
    protected String target;
    
    /** Constructor. */
    public MappingRow(String source, String target)
    {
      this.source = source;
      this.target = target;
    }
    
    /** Get the source.
    */
    public String getSource()
    {
      return source;
    }
    
    /** Get the target.
    */
    public String getTarget()
    {
      return target;
    }
    
  }
  
}

