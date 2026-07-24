locals {
  ad = var.availability_domain != "" ? var.availability_domain : data.oci_identity_availability_domains.ads.availability_domains[0].name
}

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

# Ubuntu 22.04 matching instance shape (A1=ARM, E4/E5=x86_64)
data "oci_core_images" "ubuntu_2204" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "22.04"
  shape                    = var.instance_shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

resource "oci_core_vcn" "media" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.project_name}-vcn"
  cidr_blocks    = [var.vcn_cidr]
  dns_label      = "barkymedia"
}

resource "oci_core_internet_gateway" "media" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.media.id
  display_name   = "${var.project_name}-igw"
  enabled        = true
}

resource "oci_core_route_table" "media" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.media.id
  display_name   = "${var.project_name}-rt"

  route_rules {
    network_entity_id = oci_core_internet_gateway.media.id
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
  }
}

resource "oci_core_network_security_group" "media" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.media.id
  display_name   = "${var.project_name}-nsg"
}

# SSH - restrict via ssh_ingress_cidr when possible
resource "oci_core_network_security_group_security_rule" "ssh_in" {
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "6" # TCP
  source                    = var.ssh_ingress_cidr
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "SSH"

  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}

# mediasoup / future rustrtc signaling (HTTP/WS)
resource "oci_core_network_security_group_security_rule" "signaling_tcp" {
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "mediasoup signaling TCP ${var.mediasoup_signaling_port}"

  tcp_options {
    destination_port_range {
      min = var.mediasoup_signaling_port
      max = var.mediasoup_signaling_port
    }
  }
}

# ACME HTTP-01 (Let's Encrypt free SSL via sslip.io hostname)
resource "oci_core_network_security_group_security_rule" "http_acme" {
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "HTTP ACME / redirect"

  tcp_options {
    destination_port_range {
      min = 80
      max = 80
    }
  }
}

# HTTPS / WSS reverse proxy for signaling
resource "oci_core_network_security_group_security_rule" "https_wss" {
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "HTTPS WSS TLS"

  tcp_options {
    destination_port_range {
      min = 443
      max = 443
    }
  }
}

# mediasoup RTC media (UDP range - keep small on free tier)
resource "oci_core_network_security_group_security_rule" "mediasoup_rtc_udp" {
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "17" # UDP
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "mediasoup RTC UDP ${var.mediasoup_rtc_min_port}-${var.mediasoup_rtc_max_port}"

  udp_options {
    destination_port_range {
      min = var.mediasoup_rtc_min_port
      max = var.mediasoup_rtc_max_port
    }
  }
}

# Barky custom UDP relay (cellular / CGNAT — not WebRTC)
resource "oci_core_network_security_group_security_rule" "barky_relay_udp" {
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "17"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "Barky walkie UDP relay 39000"

  udp_options {
    destination_port_range {
      min = 39000
      max = 39000
    }
  }
}

resource "oci_core_network_security_group_security_rule" "barky_relay_health_tcp" {
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "Barky relay health TCP 39001"

  tcp_options {
    destination_port_range {
      min = 39001
      max = 39001
    }
  }
}

resource "oci_core_network_security_group_security_rule" "barky_video_tcp" {
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "Barky HD video TCP relay 39002"

  tcp_options {
    destination_port_range {
      min = 39002
      max = 39002
    }
  }
}

# Optional coturn on same VM: STUN/TURN UDP 3478
resource "oci_core_network_security_group_security_rule" "turn_udp_3478" {
  count                     = var.enable_coturn ? 1 : 0
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "17"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "STUN/TURN UDP 3478"

  udp_options {
    destination_port_range {
      min = 3478
      max = 3478
    }
  }
}

# TURN over TCP 3478
resource "oci_core_network_security_group_security_rule" "turn_tcp_3478" {
  count                     = var.enable_coturn ? 1 : 0
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "TURN TCP 3478"

  tcp_options {
    destination_port_range {
      min = 3478
      max = 3478
    }
  }
}

# TURNS (TLS) 5349
resource "oci_core_network_security_group_security_rule" "turns_tcp_5349" {
  count                     = var.enable_coturn ? 1 : 0
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "TURNS TLS 5349"

  tcp_options {
    destination_port_range {
      min = 5349
      max = 5349
    }
  }
}

# Coturn relay allocation range (UDP) - narrow window for free-tier NSG
resource "oci_core_network_security_group_security_rule" "relay_udp" {
  count                     = var.enable_coturn ? 1 : 0
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "INGRESS"
  protocol                  = "17"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "TURN relay UDP ${var.relay_min_port}-${var.relay_max_port}"

  udp_options {
    destination_port_range {
      min = var.relay_min_port
      max = var.relay_max_port
    }
  }
}

resource "oci_core_network_security_group_security_rule" "egress_all" {
  network_security_group_id = oci_core_network_security_group.media.id
  direction                 = "EGRESS"
  protocol                  = "all"
  destination               = "0.0.0.0/0"
  destination_type          = "CIDR_BLOCK"
  stateless                 = false
  description               = "Allow all egress"
}

resource "oci_core_subnet" "media" {
  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.media.id
  display_name               = "${var.project_name}-public"
  cidr_block                 = var.subnet_cidr
  route_table_id             = oci_core_route_table.media.id
  prohibit_public_ip_on_vnic = false
  dns_label                  = "mediapub"
}

resource "oci_core_instance" "media" {
  compartment_id      = var.compartment_ocid
  availability_domain = local.ad
  # Do NOT set fault_domain - Oracle often fails free A1 when FD is pinned.
  display_name        = "${var.project_name}-hub"
  shape               = var.instance_shape

  shape_config {
    ocpus         = var.ocpu_count
    memory_in_gbs = var.memory_in_gbs
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.media.id
    display_name     = "${var.project_name}-vnic"
    assign_public_ip = true
    nsg_ids          = [oci_core_network_security_group.media.id]
    hostname_label   = "barkymedia"
  }

  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu_2204.images[0].id
    boot_volume_size_in_gbs = var.boot_volume_size_in_gbs
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data = base64encode(templatefile("${path.module}/user-data.tftpl", {
      turn_realm               = var.turn_realm
      turn_user                = var.turn_user
      turn_password            = var.turn_password
      turn_auth_secret         = var.turn_auth_secret
      relay_min                = var.relay_min_port
      relay_max                = var.relay_max_port
      enable_coturn            = var.enable_coturn
      mediasoup_signaling_port = var.mediasoup_signaling_port
      mediasoup_rtc_min_port   = var.mediasoup_rtc_min_port
      mediasoup_rtc_max_port   = var.mediasoup_rtc_max_port
    }))
  }

  lifecycle {
    ignore_changes = [
      source_details[0].source_id,
    ]
  }
}

data "oci_core_vnic_attachments" "instance" {
  compartment_id      = var.compartment_ocid
  instance_id         = oci_core_instance.media.id
  availability_domain = local.ad
}

data "oci_core_vnic" "instance" {
  vnic_id = data.oci_core_vnic_attachments.instance.vnic_attachments[0].vnic_id
}
