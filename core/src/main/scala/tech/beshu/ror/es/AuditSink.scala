package tech.beshu.ror.es

trait AuditSink {
  def submit(indexName: String, documentId: String, jsonRecord: String): Unit
}
