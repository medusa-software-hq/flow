tasks.register("hello") { doLast { println("hello from gradle") } }

tasks.register("boom") { doLast { throw GradleException("boom") } }
