<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib uri="http://tiles.apache.org/tags-tiles" prefix="tiles"%>

<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title><tiles:getAsString name="title"/></title>
    <tiles:insertAttribute name="styles"/>
</head>
<body>
    <tiles:insertAttribute name="header"/>

    <main class="container main-content py-4">
        <tiles:insertAttribute name="banner"/>

        <div class="row g-4 align-items-start">
            <tiles:insertAttribute name="sidebar"/>

            <section class="col-lg-9">
                <tiles:insertAttribute name="body"/>
            </section>
        </div>
    </main>

    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>