About
=====

This program will connect to your Unifi controller and local access points to
make tuning recommendations, such as channel selection and transmit power.

Building
========

	mvn clean package

Requirements
============

* A computer to run this application, with Java8 runtime (eg Mac or Linux; Windows untested)
* A Unifi controller, and login credentials (either cloud or local, as long as it's accessible)
* Access points accessible via ssh from where this application is run
* A client device that approximates the lowest capabilities of client devices typically on the network (eg if your network has smartphones, use a smartphone)
* iPerf on the client and iPerf on a hardwired computer on the network (to generate network traffic)

Running
=======

Execute this application:

	java -jar <path_to_application.jar> <server_hostname_or_ip> <admin_username> <admin_pw> <site_name_or_id>

Example:

	java -jar app.jar 192.168.0.2 admin_bob bobpassword bobs_site

While the application is running, perform a task on the client which will create constant network traffic, eg iPerf: iperf -c 192.168.0.3 -b 100 -u -t 999

Move the client throughout the area of wifi coverage, to have the device roam between access points.


Description of Logic
====================

The application will log into the controller with the given credentials, then retrieve the site and access point details. The application will simultaneously ssh into each access point to retrieve near-real-time client connection statistics, and continuously monitor the signal strength of the client connection to the access point. The application will make tuning recommendations, which may conflict with local site requirements. The application cannot accommodate for inadequate coverage due to an insufficient number of access points.

The recommendations provided by the application are based on the belief that if the transmit power of an access point is too high, the client will "hang on" to the connection for too long, and not roam at the optimal time. Likewise, if the transmit power of the access point is too low, the client will roam too soon. Basically, there should not be a great discrepancy between the transmit power of the access point and the transmit power of the client, to facilitate good roaming behavior.
