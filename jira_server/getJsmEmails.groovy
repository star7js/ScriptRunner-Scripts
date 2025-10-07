import com.onresolve.scriptrunner.db.DatabaseUtil

def poolName = 'JiraDB'  // Replace with your configured pool name

try {
    def fullReport = DatabaseUtil.withSql(poolName) { sql ->
        // JSM Channels Query (incoming emails for JSM projects)
        def jsmQuery = '''
        SELECT es."EMAIL_ADDRESS" as "Email Channel", 
               p."pname" as "Project Name", 
               p."pkey" as "Project Key", 
               vpf."NAME" as "Request Type"
        FROM "AO_54307E_EMAILCHANNELSETTING" es
        INNER JOIN "AO_54307E_VIEWPORT" vp ON vp."ID" = es."SERVICE_DESK_ID"
        INNER JOIN project p ON p.id = vp."PROJECT_ID"
        INNER JOIN "AO_54307E_VIEWPORTFORM" vpf ON es."REQUEST_TYPE_ID" = vpf."ID"
        ORDER BY p."pname" ASC
        '''
        
        def jsmResults = []
        sql.eachRow(jsmQuery) { row ->
            jsmResults << [
                email: row['Email Channel'], 
                projectName: row['Project Name'], 
                projectKey: row['Project Key'], 
                requestType: row['Request Type']
            ]
        }
        
        def jsmReport = "JSM Email Channels (Incoming for Ticket Creation):\n"
        if (jsmResults.isEmpty()) {
            jsmReport += "No JSM channels found.\n"
        } else {
            jsmReport += "| Email Channel | Project Name | Project Key | Request Type |\n"
            jsmReport += "|---------------|--------------|-------------|--------------|\n"
            jsmReport += jsmResults.collect { "${it.email} | ${it.projectName} | ${it.projectKey} | ${it.requestType}" }.join('\n') + "\n"
        }
        
        // General Mail Handlers Query (non-JSM incoming handlers)
        def generalQuery = '''
        SELECT sc.id AS "Handler ID", sc.servicename AS "Handler Name", 
               ms.servername AS "Mail Server Name", ms.username AS "Email Address",
               ps.propertyvalue AS "Handler Params"
        FROM serviceconfig sc
        INNER JOIN propertyentry pe ON pe.property_key = 'popserver' AND pe.entity_id = sc.id
        INNER JOIN propertystring ps ON ps.id = pe.id
        INNER JOIN mailserver ms ON CAST(ps.propertyvalue AS integer) = ms.id
        WHERE sc.clazz = 'com.atlassian.jira.service.services.mail.MailFetcherService'
        '''
        
        def generalResults = []
        sql.eachRow(generalQuery) { row ->
            def params = row['Handler Params'] ?: ""
            def projectKey = params =~ /project=([A-Z0-9]+)/ ? (params =~ /project=([A-Z0-9]+)/)[0][1] : "Unknown"
            generalResults << [
                email: row['Email Address'], 
                handlerName: row['Handler Name'], 
                projectKey: projectKey
            ]
        }
        
        def generalReport = "\nGeneral Jira Mail Handlers (Incoming for Non-JSM Projects):\n"
        if (generalResults.isEmpty()) {
            generalReport += "No general mail handlers found.\n"
        } else {
            generalReport += "| Email Address | Handler Name | Associated Project Key |\n"
            generalReport += "|--------------|--------------|------------------------|\n"
            generalReport += generalResults.collect { "${it.email} | ${it.handlerName} | ${it.projectKey}" }.join('\n') + "\n"
        }
        
        return jsmReport + generalReport
    }
    
    log.info fullReport
    return fullReport  // Displays in console
} catch (Exception e) {
    log.error "Query failed: ${e.message}"
    return "Error: ${e.message}"
}