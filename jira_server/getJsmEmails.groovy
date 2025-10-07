import com.onresolve.scriptrunner.db.DatabaseUtil

def poolName = 'JiraDB'  // Replace with your configured pool name

try {
    def report = DatabaseUtil.withSql(poolName) { sql ->
        def query = '''
        SELECT es."EMAIL_ADDRESS" as "Email Channel", 
               p."pname" as "Project Name", 
               p."pkey" as "Project Key", 
               vpf."NAME" as "Request Type",
               es."ENABLED" as "Enabled"  -- Add this if available; remove if column doesn't exist
        FROM "AO_54307E_EMAILCHANNELSETTING" es
        INNER JOIN "AO_54307E_VIEWPORT" vp ON vp."ID" = es."SERVICE_DESK_ID"
        INNER JOIN project p ON p.id = vp."PROJECT_ID"
        INNER JOIN "AO_54307E_VIEWPORTFORM" vpf ON es."REQUEST_TYPE_ID" = vpf."ID"
        ORDER BY p."pname" ASC;  -- Sort by project name alphabetically
        '''
        // For MySQL/Oracle: Remove quotes, e.g., SELECT es.EMAIL_ADDRESS as Email_Channel, ...
        
        def results = []
        sql.eachRow(query) { row ->
            results << [
                email: row['Email Channel'], 
                projectName: row['Project Name'], 
                projectKey: row['Project Key'], 
                requestType: row['Request Type'],
                enabled: row['Enabled'] ?: 'Unknown'  // Fallback if column missing
            ]
        }
        
        if (results.isEmpty()) {
            log.warn "No email channels found. Check if any are configured in JSM projects."
            return "No email channels found"
        }
        
        // Format as Markdown table for readability
        def table = "| Email Channel | Project Name | Project Key | Request Type | Enabled |\n"
        table += "|---------------|--------------|-------------|--------------|---------|\n"
        table += results.collect { "${it.email} | ${it.projectName} | ${it.projectKey} | ${it.requestType} | ${it.enabled}" }.join('\n')
        
        return table
    }
    
    log.info "Email Channels Report:\n${report}"
    return report  // Displays in console
    
    // Optional: Email the report (uncomment and configure)
    // def mailServer = ComponentAccessor.getMailServerManager().getDefaultSMTPMailServer()
    // if (mailServer) {
    //     mailServer.createSession().sendMail('from@example.com', 'to@example.com', 'JSM Email Report', report, 'text/plain')
    // }
    
    // Optional: Write to file (uncomment; requires file write permissions)
    // new File('/path/to/report.txt').text = report
} catch (Exception e) {
    log.error "Database query failed: ${e.message}"
    return "Error: ${e.message}"
}