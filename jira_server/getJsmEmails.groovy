import com.onresolve.scriptrunner.db.DatabaseUtil

def poolName = 'JiraDB'  // Replace with your configured pool name

try {
    def report = DatabaseUtil.withSql(poolName) { sql ->
        def query = '''
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
        // No trailing semicolon; for MySQL/Oracle, remove quotes around table/column names
        
        // First, count rows to debug
        def countQuery = '''
        SELECT COUNT(*) as total
        FROM "AO_54307E_EMAILCHANNELSETTING" es
        INNER JOIN "AO_54307E_VIEWPORT" vp ON vp."ID" = es."SERVICE_DESK_ID"
        INNER JOIN project p ON p.id = vp."PROJECT_ID"
        INNER JOIN "AO_54307E_VIEWPORTFORM" vpf ON es."REQUEST_TYPE_ID" = vpf."ID"
        '''
        def rowCount = sql.firstRow(countQuery)?.total ?: 0
        log.info "Found ${rowCount} email channel configurations."
        
        def results = []
        sql.eachRow(query) { row ->
            results << [
                email: row['Email Channel'], 
                projectName: row['Project Name'], 
                projectKey: row['Project Key'], 
                requestType: row['Request Type']
            ]
        }
        
        if (results.isEmpty()) {
            log.warn "No email channels found. Check if any are configured in JSM projects (Project Settings > Channels > Email)."
            return "No email channels found"
        }
        
        // Format as Markdown table for readability
        def table = "| Email Channel | Project Name | Project Key | Request Type |\n"
        table += "|---------------|--------------|-------------|--------------|\n"
        table += results.collect { "${it.email} | ${it.projectName} | ${it.projectKey} | ${it.requestType}" }.join('\n')
        
        return table
    }
    
    log.info "Email Channels Report:\n${report}"
    return report  // Displays in console
} catch (Exception e) {
    log.error "Database query failed: ${e.message}"
    return "Error: ${e.message}"
}